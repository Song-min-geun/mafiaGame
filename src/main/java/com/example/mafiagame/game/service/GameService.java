package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameService {

    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final SimpMessagingTemplate messagingTemplate;

    public Game createGame(String roomId, List<GamePlayer> playerList) {
        String gameId = "game_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);
        Game game = Game.builder()
                .gameId(gameId)
                .roomId(roomId)
                .status(GameStatus.IN_PROGRESS)
                .players(playerList)
                .build();
        playerList.forEach(player -> player.setGame(game));
        games.put(gameId, game);
        log.info("게임 생성됨: {}", gameId);
        return game;
    }

    public void assignRoles(String gameId) {
        Game game = getGame(gameId);
        if (game == null)
            return;
        List<GamePlayer> players = game.getPlayers();
        int playerCount = players.size();
        int mafiaCount = Math.max(1, playerCount / 4);
        List<PlayerRole> roles = new ArrayList<>();
        for (int i = 0; i < mafiaCount; i++)
            roles.add(PlayerRole.MAFIA);
        roles.add(PlayerRole.DOCTOR);
        roles.add(PlayerRole.POLICE);

        while (roles.size() < playerCount)
            roles.add(PlayerRole.CITIZEN);
        Collections.shuffle(roles);

        for (int i = 0; i < playerCount; i++) {
            GamePlayer player = players.get(i);
            PlayerRole assignedRole = roles.get(i);
            player.setRole(assignedRole);

            // Send role to each player privately
            String playerId = player.getPlayerId();
            log.info("역할 배정 메시지 전송 시도: playerId={}, role={}", playerId, assignedRole);

            try {
                Map<String, Object> message = Map.of(
                        "type", "ROLE_ASSIGNED",
                        "role", assignedRole.name(),
                        "roleDescription", getRoleDescription(assignedRole));
                log.info("전송할 메시지 내용: {}", message);

                // 개인 전용 큐로 메시지 전송 (/user/queue/private)
                sendPrivateMessage(playerId, Map.of(
                        "type", "ROLE_ASSIGNED",
                        "role", assignedRole.name(),
                        "roleDescription", getRoleDescription(assignedRole)));
                log.info("역할 배정 메시지 전송 완료: playerId={}", playerId);

            } catch (Exception e) {
                log.error("역할 배정 메시지 전송 실패: playerId={}, error={}", playerId, e.getMessage(), e);
            }
        }
        log.info("역할 배정 및 비공개 메시지 전송 완료: {}", gameId);
    }

    public void startGame(String gameId) {
        Game game = getGame(gameId);
        if (game == null)
            throw new RuntimeException("게임을 찾을 수 없습니다: " + gameId);
        game.setStatus(GameStatus.IN_PROGRESS);
        game.setStartTime(LocalDateTime.now());
        toNextDayPhase(game);
        log.info("게임 시작됨: {} (낮 대화: {}초)", gameId, game.getRemainingTime());
    }

    public void endGame(String gameId, String winner) {
        Game game = getGame(gameId);
        if (game == null || game.getStatus() == GameStatus.ENDED)
            return;
        game.setStatus(GameStatus.ENDED);
        game.setWinner(winner);
        game.setEndTime(LocalDateTime.now());
        messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(),
                Map.of("type", "GAME_ENDED", "winner", winner, "players", game.getPlayers()));
        log.info("게임 종료: {} - 승리자: {}", gameId, winner);
        games.remove(gameId);
    }

    public void advancePhase(String gameId) {
        Game game = getGame(gameId);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS)
            return;
        switch (game.getGamePhase()) {
            case DAY_DISCUSSION -> toPhase(game, GamePhase.DAY_VOTING, 60);
            case DAY_VOTING -> processDayVoting(game);
            case DAY_FINAL_DEFENSE -> toPhase(game, GamePhase.DAY_FINAL_VOTING, 60);
            case DAY_FINAL_VOTING -> processFinalVoting(game);
            case NIGHT_ACTION -> processNight(game);
        }
        sendPhaseSwitchMessage(game);
    }

    public void vote(String gameId, String voterId, String targetId) {
        Game game = getGame(gameId);
        if (game == null || game.getGamePhase() != GamePhase.DAY_VOTING)
            return;
        if (findActivePlayerById(game, voterId) == null)
            return;
        game.getVotes().removeIf(vote -> vote.getVoterId().equals(voterId));
        game.getVotes().add(Vote.builder().voterId(voterId).targetId(targetId).build());
    }

    public void finalVote(String gameId, String voterId, String voteChoice) {
        Game game = getGame(gameId);
        if (game == null || game.getGamePhase() != GamePhase.DAY_FINAL_VOTING)
            return;
        if (findActivePlayerById(game, voterId) == null || voterId.equals(game.getVotedPlayerId()))
            return;
        game.getFinalVotes().removeIf(vote -> vote.getVoterId().equals(voterId));
        game.getFinalVotes().add(FinalVote.builder().voterId(voterId).vote(voteChoice).build());
    }

    public void nightAction(String gameId, String actorId, String targetId) {
        Game game = getGame(gameId);
        GamePlayer actor = findActivePlayerById(game, actorId);
        if (game == null || game.getGamePhase() != GamePhase.NIGHT_ACTION || actor == null
                || actor.getRole() == PlayerRole.CITIZEN)
            return;
        game.getNightActions().removeIf(action -> action.getActorId().equals(actorId));
        game.getNightActions().add(NightAction.builder().actorId(actorId).targetId(targetId).build());
        if (actor.getRole() == PlayerRole.POLICE) {
            GamePlayer target = findPlayerById(game, targetId);
            if (target != null)
                sendPoliceInvestigationResult(actor, target);
        }
    }

    private void processDayVoting(Game game) {
        List<String> topVotedPlayers = getTopVotedPlayers(game);
        if (topVotedPlayers.size() != 1) {
            sendSystemMessage(game.getRoomId(), "투표가 무효 처리되어 밤으로 넘어갑니다.");
            toNightPhase(game);
        } else {
            String votedPlayerId = topVotedPlayers.get(0);
            game.setVotedPlayerId(votedPlayerId);
            toPhase(game, GamePhase.DAY_FINAL_DEFENSE, 60);
            Optional.ofNullable(findPlayerById(game, votedPlayerId))
                    .ifPresent(player -> sendSystemMessage(game.getRoomId(),
                            String.format("투표 결과 %s님이 최다 득표자가 되었습니다. 최후 변론을 시작합니다.", player.getPlayerName())));
        }
        game.getVotes().clear();
    }

    private void processFinalVoting(Game game) {
        long agreeCount = game.getFinalVotes().stream().filter(v -> "AGREE".equals(v.getVote())).count();
        long disagreeCount = game.getFinalVotes().stream().filter(v -> "DISAGREE".equals(v.getVote())).count();
        if (agreeCount > disagreeCount) {
            Optional.ofNullable(findPlayerById(game, game.getVotedPlayerId())).ifPresent(eliminatedPlayer -> {
                eliminatedPlayer.setAlive(false);
                sendSystemMessage(game.getRoomId(),
                        String.format("최종 투표 결과, %s님이 처형되었습니다.", eliminatedPlayer.getPlayerName()));
            });
        }
        if (checkGameEnd(game))
            return;
        toNightPhase(game);
    }

    private void processNight(Game game) {
        processNightActions(game);
        if (checkGameEnd(game))
            return;
        toNextDayPhase(game);
    }

    private void processNightActions(Game game) {
        Map<String, Long> mafiaVotes = game.getNightActions().stream()
                .filter(action -> Optional.ofNullable(findPlayerById(game, action.getActorId()))
                        .map(GamePlayer::getRole).orElse(null) == PlayerRole.MAFIA)
                .collect(Collectors.groupingBy(NightAction::getTargetId, Collectors.counting()));
        String mafiaTargetId = getTopVotedPlayers(mafiaVotes).stream().findFirst().orElse(null);
        String doctorTargetId = game.getNightActions().stream()
                .filter(action -> Optional.ofNullable(findPlayerById(game, action.getActorId()))
                        .map(GamePlayer::getRole).orElse(null) == PlayerRole.DOCTOR)
                .map(NightAction::getTargetId).findFirst().orElse(null);
        if (mafiaTargetId != null && !mafiaTargetId.equals(doctorTargetId)) {
            Optional.ofNullable(findPlayerById(game, mafiaTargetId)).ifPresent(killed -> {
                killed.setAlive(false);
                sendSystemMessage(game.getRoomId(), "지난 밤, " + killed.getPlayerName() + "님이 마피아의 공격으로 사망했습니다.");
            });
        } else {
            sendSystemMessage(game.getRoomId(), "지난 밤, 아무 일도 일어나지 않았습니다.");
        }
    }

    private boolean checkGameEnd(Game game) {
        long aliveMafia = game.getPlayers().stream().filter(p -> p.isAlive() && p.getRole() == PlayerRole.MAFIA)
                .count();
        long aliveCitizens = game.getPlayers().stream().filter(p -> p.isAlive() && p.getRole() != PlayerRole.MAFIA)
                .count();
        if (aliveMafia >= aliveCitizens) {
            endGame(game.getGameId(), "MAFIA");
            return true;
        }
        if (aliveMafia == 0) {
            endGame(game.getGameId(), "CITIZEN");
            return true;
        }
        return false;
    }

    private void toPhase(Game game, GamePhase phase, int time) {
        game.setGamePhase(phase);
        game.setRemainingTime(time);
    }

    private void toNightPhase(Game game) {
        toPhase(game, GamePhase.NIGHT_ACTION, 15);
        game.setIsDay(false);
        game.getVotes().clear();
        game.getFinalVotes().clear();
        game.setVotedPlayerId(null);
    }

    private void toNextDayPhase(Game game) {
        game.setCurrentPhase(game.getCurrentPhase() + 1);
        toPhase(game, GamePhase.DAY_DISCUSSION, 60);
        game.setIsDay(true);
        game.getNightActions().clear();
    }

    public Game getGame(String gameId) {
        return games.get(gameId);
    }

    public Game getGameByRoomId(String roomId) {
        return games.values().stream()
                .filter(game -> game.getRoomId().equals(roomId) && game.getStatus() == GameStatus.IN_PROGRESS)
                .findFirst().orElse(null);
    }

    public Set<String> getActiveGameIds() {
        return games.keySet();
    }

    public boolean updateTime(String gameId, String playerId, int seconds) {
        log.info("updateTime 호출됨: gameId={}, playerId={}, seconds={}", gameId, playerId, seconds);
        Game game = getGame(gameId);
        if (game == null) {
            log.error("updateTime 실패: 게임을 찾을 수 없음 (gameId: {})", gameId);
            return false;
        }

        // ❗ 추가: 낮 토론 시간 외에는 시간 조절 불가
        if (game.getGamePhase() != GamePhase.DAY_DISCUSSION) {
            log.warn("updateTime 실패: 현재 페이즈({})에서는 시간 조절 불가", game.getGamePhase());
            return false;
        }

        game.setRemainingTime(Math.max(0, game.getRemainingTime() + seconds));
        sendTimerUpdate(game);

        GamePlayer player = findPlayerById(game, playerId);
        if (player != null) {
            log.info("플레이어 찾음: {}. 시스템 메시지를 전송합니다.", player.getPlayerName());
            sendSystemMessage(game.getRoomId(), String.format("%s님이 시간을 %d초 %s했습니다.", player.getPlayerName(),
                    Math.abs(seconds), seconds > 0 ? "연장" : "단축"));
        } else {
            log.error("updateTime 실패: 플레이어를 찾을 수 없음 (playerId: {})", playerId);
        }
        return true;
    }

    private List<String> getTopVotedPlayers(Map<String, Long> voteCounts) {
        if (voteCounts.isEmpty())
            return new ArrayList<>();
        long maxVotes = Collections.max(voteCounts.values());
        return voteCounts.entrySet().stream().filter(entry -> entry.getValue() == maxVotes).map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private List<String> getTopVotedPlayers(Game game) {
        if (game.getVotes().isEmpty())
            return new ArrayList<>();
        Map<String, Long> voteCounts = game.getVotes().stream()
                .collect(Collectors.groupingBy(Vote::getTargetId, Collectors.counting()));
        return getTopVotedPlayers(voteCounts);
    }

    private GamePlayer findPlayerById(Game game, String playerId) {
        return game.getPlayers().stream().filter(p -> p.getPlayerId().equals(playerId)).findFirst().orElse(null);
    }

    private GamePlayer findActivePlayerById(Game game, String playerId) {
        GamePlayer p = findPlayerById(game, playerId);
        return (p != null && p.isAlive()) ? p : null;
    }

    public void sendTimerUpdate(Game game) {
        messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(),
                Map.of("type", "TIMER_UPDATE", "gameId", game.getGameId(), "remainingTime", game.getRemainingTime(),
                        "gamePhase", game.getGamePhase(), "currentPhase", game.getCurrentPhase(), "isDay",
                        game.isDay()));
    }

    private void sendSystemMessage(String roomId, String content) {
        messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of("type", "SYSTEM", "content", content));
    }

    private void sendPhaseSwitchMessage(Game game) {
        messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(),
                Map.of("type", "PHASE_SWITCHED", "game", game));
    }

    private void sendPoliceInvestigationResult(GamePlayer police, GamePlayer target) {
        String policeId = police.getPlayerId();
        log.info("경찰 조사 결과 전송: policeId={}, target={}", policeId, target.getPlayerName());

        // 개인 메시지로 전송 (헤더 포함)
        sendPrivateMessage(policeId, Map.of(
                "type", "PRIVATE_MESSAGE",
                "targetPlayerId", policeId,
                "messageType", "POLICE_INVESTIGATION",
                "content", String.format("경찰 조사 결과: %s님은 [ %s ] 입니다.",
                        target.getPlayerName(),
                        target.getRole() == PlayerRole.MAFIA ? "마피아" : "시민")));
    }

    private String getRoleDescription(PlayerRole role) {
        return switch (role) {
            case MAFIA -> "밤에 한 명을 지목하여 제거할 수 있습니다.";
            case POLICE -> "밤에 한 명을 지목하여 마피아인지 확인할 수 있습니다.";
            case DOCTOR -> "밤에 한 명을 지목하여 마피아의 공격으로부터 보호할 수 있습니다.";
            case CITIZEN -> "특별한 능력이 없습니다. 추리를 통해 마피아를 찾아내세요.";
        };
    }

    private void sendPrivateMessage(String playerId, Map<String, Object> payload) {
        // SimpUserRegistry 문제로 인해 convertAndSendToUser 대신 직접 토픽을 사용합니다.
        // 클라이언트는 /topic/private/{userId}를 구독해야 합니다.
        String destination = "/topic/private/" + playerId;
        log.info("sendPrivateMessage 호출: destination={}, payload={}", destination, payload);

        try {
            messagingTemplate.convertAndSend(destination, payload);
            log.info("sendPrivateMessage 성공: destination={}", destination);
        } catch (Exception e) {
            log.error("sendPrivateMessage 실패: destination={}, error={}", destination, e.getMessage(), e);
        }
    }
}
