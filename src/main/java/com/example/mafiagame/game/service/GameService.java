// src/main/java/com/example/mafiagame/game/service/GameService.java

package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.*;
import com.example.mafiagame.game.dto.request.NightResultMessageDto;
import com.example.mafiagame.game.dto.request.PoliceResultMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameService {

    private final Map<String, Game> games = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> deadPlayersChatRooms = new ConcurrentHashMap<>();

    @Autowired
    private GameTimerService gameTimerService;
    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    public Game createGame(String roomId, List<GamePlayer> playerList, int maxPlayers, boolean hasDoctor, boolean hasPolice) {
        String gameId = "game_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);
        Game game = Game.builder()
                .gameId(gameId)
                .roomId(roomId)
                .status(GameStatus.WAITING)
                .players(new Players(playerList)) // Players 객체로 감싸서 생성
                .currentPhase(0)
                .isDay(true)
                .votes(new HashMap<>())
                .nightActions(new HashMap<>())
                .maxPlayers(maxPlayers)
                .hasDoctor(hasDoctor)
                .hasPolice(hasPolice)
                .build();
        games.put(gameId, game);
        gameTimerService.registerGame(game);
        log.info("게임 생성됨: {}", gameId);
        return game;
    }

    public Game startGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) throw new RuntimeException("게임을 찾을 수 없습니다: " + gameId);
        if (game.getPlayers().size() < 4) throw new RuntimeException("최소 4명의 플레이어가 필요합니다.");

        int mafiaCount = Math.max(1, game.getPlayers().size() / 3);
        game.getPlayers().assignRoles(mafiaCount, game.isHasDoctor(), game.isHasPolice());

        game.setStatus(GameStatus.STARTING);
        game.setStartTime(LocalDateTime.now());
        game.setCurrentPhase(1);
        game.setIsDay(true);
        game.setGamePhase(GamePhase.DAY_DISCUSSION);
        game.setPhaseStartTime(LocalDateTime.now());
        game.setRemainingTime(60);

        for (GamePlayer player : game.getPlayers().getAsList()) {
            game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
        }

        gameTimerService.registerGame(game);
        gameTimerService.startGameTimer(gameId);
        log.info("게임 시작됨: {} (낮 대화: {}초)", gameId, game.getRemainingTime());
        return game;
    }

    public void processNightAction(String gameId, String playerId, String targetId) {
        Game game = games.get(gameId);
        if (game == null) return;

        GamePlayer player = game.getPlayers().findById(playerId);
        GamePlayer target = game.getPlayers().findById(targetId);

        if (player == null || !player.isAlive()) throw new IllegalArgumentException("플레이어가 유효하지 않거나 생존하지 않습니다: " + playerId);
        if (player.getRole() == PlayerRole.CITIZEN) throw new IllegalArgumentException("시민은 밤 액션을 할 수 없습니다: " + player.getPlayerName());

        if (player.getRole() == PlayerRole.POLICE && target != null) {
            sendPoliceInvestigationResult(game, player, target);
        }

        game.getNightActions().put(playerId, targetId);
        sendNightActionResult(game, player, targetId);
        log.info("밤 액션 저장: {} ({}) -> {}", player.getPlayerName(), player.getRole(), targetId);
    }

    public void processNightResults(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return;

        Map<String, String> nightActions = game.getNightActions();
        game.getPlayers().resetVoteCounts();

        String mafiaTarget = null;
        String doctorTarget = null;

        for (GamePlayer player : game.getPlayers().getAsList()) {
            if (!player.isAlive()) continue;
            String targetId = nightActions.get(player.getPlayerId());
            if (targetId == null) continue;
            if (player.getRole() == PlayerRole.MAFIA) mafiaTarget = targetId;
            if (player.getRole() == PlayerRole.DOCTOR) doctorTarget = targetId;
        }

        if (mafiaTarget != null && !mafiaTarget.equals(doctorTarget)) {
            GamePlayer target = game.getPlayers().findById(mafiaTarget);
            if (target != null && target.isAlive()) {
                target.setIsAlive(false);
                log.info("플레이어 사망: {}", target.getPlayerName());
                gameTimerService.addDeadPlayerToChatRoom(game.getRoomId(), mafiaTarget);
            }
        }

        sendNightResultMessage(game, mafiaTarget, doctorTarget);
        String winner = checkGameEnd(gameId);
        if (winner != null) {
            endGame(gameId, winner);
            return;
        }

        game.getNightActions().clear();
        game.setIsDay(true);
    }

    private void sendNightActionResult(Game game, GamePlayer player, String targetId) {
        try {
            GamePlayer target = game.getPlayers().findById(targetId);
            if (target == null) return;

            Map<String, Object> actionMessage = new HashMap<>();
            actionMessage.put("type", "NIGHT_ACTION_RESULT");
            actionMessage.put("gameId", game.getGameId());
            actionMessage.put("roomId", game.getRoomId());
            actionMessage.put("playerId", player.getPlayerId());
            actionMessage.put("targetName", target.getPlayerName());
            actionMessage.put("senderId", "SYSTEM");
            actionMessage.put("senderName", "시스템");
            actionMessage.put("timestamp", LocalDateTime.now().toString());
            actionMessage.put("content", String.format("%s님이 선택되었습니다.", target.getPlayerName()));

            messagingTemplate.convertAndSendToUser(player.getPlayerId(), "/queue/private", actionMessage);
            log.info("밤 액션 결과 메시지 전송: {} -> {}", player.getPlayerName(), target.getPlayerName());
        } catch (Exception e) {
            log.error("밤 액션 결과 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }

    private void sendNightResultMessage(Game game, String mafiaTarget, String doctorTarget) {
        try {
            String resultMessage = "이번 밤에 아무 일도 일어나지 않았습니다.";
            String killedPlayerId = null;

            if (mafiaTarget != null && !mafiaTarget.equals(doctorTarget)) {
                GamePlayer target = game.getPlayers().findById(mafiaTarget);
                if (target != null) {
                    killedPlayerId = target.getPlayerId();
                    resultMessage = String.format("%s님이 살해되었습니다.", target.getPlayerName());
                }
            }
            NightResultMessageDto nightResultMessage = new NightResultMessageDto("SYSTEM", "SYSTEM", "시스템", game.getRoomId(), resultMessage, LocalDateTime.now().toString(), killedPlayerId);
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), nightResultMessage);
            log.info("밤 결과 메시지 전송: {}", resultMessage);
        } catch (Exception e) {
            log.error("밤 결과 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }

    private void sendPoliceInvestigationResult(Game game, GamePlayer police, GamePlayer target) {
        try {
            String content = String.format("🔍 조사 결과: %s님은 %s", target.getPlayerName(), target.getRole() == PlayerRole.MAFIA ? "마피아입니다!" : "시민입니다.");
            PoliceResultMessage policeResultMessage = new PoliceResultMessage("POLICE_INVESTIGATION_RESULT", "SYSTEM", "시스템", game.getRoomId(), police.getPlayerId(), content, LocalDateTime.now().toString());
            messagingTemplate.convertAndSendToUser(police.getPlayerId(), "/queue/private", policeResultMessage);
            log.info("경찰 조사 결과 전송: {} -> {}", police.getPlayerName(), target.getPlayerName());
        } catch (Exception e) {
            log.error("경찰 조사 결과 전송 실패: {}", game.getGameId(), e);
        }
    }

    public void vote(String gameId, String voterId, String targetId) {
        Game game = games.get(gameId);
        if (game == null) return;

        GamePlayer voter = game.getPlayers().findById(voterId);
        if (voter == null || !voter.isAlive()) {
            return; // 죽은 플레이어는 투표 불가
        }

        game.getVotes().put(voterId, targetId);
        GamePlayer target = game.getPlayers().findById(targetId);
        if (target != null) {
            target.setVoteCount(target.getVoteCount() + 1);
        }
    }

    public void processVote(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return;

        String votedPlayerId = getVotedPlayerId(gameId);
        if (votedPlayerId != null) {
            log.info("투표 결과 플레이어: {} - 최종 변론 권한 부여", votedPlayerId);
        }
    }

    public void processFinalVote(String gameId, String playerId, String vote) {
        Game game = games.get(gameId);
        if (game == null) throw new IllegalArgumentException("게임을 찾을 수 없습니다: " + gameId);

        GamePlayer voter = game.getPlayers().findById(playerId);
        if (voter == null || !voter.isAlive()) throw new IllegalArgumentException("투표자가 존재하지 않거나 생존하지 않습니다: " + playerId);

        String votedPlayerId = getVotedPlayerId(gameId);
        if (votedPlayerId != null && votedPlayerId.equals(playerId)) throw new IllegalArgumentException("최다 득표자는 자신에게 투표할 수 없습니다: " + voter.getPlayerName());

        game.getFinalVotes().put(playerId, vote);
        log.info("최종 투표 기록: {} -> {}", voter.getPlayerName(), vote);
    }

    public String getVotedPlayerId(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;
        if (game.getVotedPlayerId() != null) return game.getVotedPlayerId();

        Map<String, String> votes = game.getVotes();
        if (votes.isEmpty()) return null;

        Map<String, Long> voteCounts = votes.values().stream()
                .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        long maxVotes = Collections.max(voteCounts.values());

        List<String> tiedPlayers = voteCounts.entrySet().stream()
                .filter(entry -> entry.getValue() == maxVotes)
                .map(Map.Entry::getKey)
                .toList();

        return tiedPlayers.get(new Random().nextInt(tiedPlayers.size()));
    }

    public String processVoteResults(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;

        String eliminatedPlayerId = getVotedPlayerId(gameId);

        if (eliminatedPlayerId != null) {
            GamePlayer eliminated = game.getPlayers().findById(eliminatedPlayerId);
            if (eliminated != null) {
                eliminated.setIsAlive(false);
                log.info("투표로 제거됨: {}", eliminated.getPlayerName());
                addDeadPlayerToChatRoom(game.getRoomId(), eliminatedPlayerId);
            }
        }

        String winner = checkGameEnd(gameId);
        if (winner != null) {
            endGame(gameId, winner);
            return eliminatedPlayerId;
        }

        game.getVotes().clear();
        game.setIsDay(false);
        game.setCurrentPhase(game.getCurrentPhase() + 1);
        sendVoteResultUpdate(game, eliminatedPlayerId);
        return eliminatedPlayerId;
    }

    public void addDeadPlayerToChatRoom(String roomId, String playerId) {
        deadPlayersChatRooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        log.info("죽은 플레이어 채팅방에 추가: roomId={}, playerId={}", roomId, playerId);
    }

    public Set<String> getDeadPlayersInChatRoom(String roomId) {
        return deadPlayersChatRooms.getOrDefault(roomId, Collections.emptySet());
    }

    public boolean isPlayerInDeadChatRoom(String roomId, String playerId) {
        return deadPlayersChatRooms.getOrDefault(roomId, Collections.emptySet()).contains(playerId);
    }

    public void clearDeadPlayersChatRoom(String roomId) {
        deadPlayersChatRooms.remove(roomId);
        log.info("죽은 플레이어 채팅방 정리: roomId={}", roomId);
    }

    public Game getGameByRoomId(String roomId) {
        return games.values().stream()
                .filter(g -> g.getRoomId().equals(roomId))
                .findFirst()
                .orElse(null);
    }

    private void sendVoteResultUpdate(Game game, String eliminatedPlayerId) {
        try {
            String eliminatedPlayerName = null;
            if (eliminatedPlayerId != null) {
                GamePlayer eliminated = game.getPlayers().findById(eliminatedPlayerId);
                if (eliminated != null) {
                    eliminatedPlayerName = eliminated.getPlayerName();
                }
            }

            Map<String, Object> message = new HashMap<>();
            message.put("type", "VOTE_RESULT_UPDATE");
            message.put("gameId", game.getGameId());
            message.put("roomId", game.getRoomId());
            message.put("players", game.getPlayers().getAsList());
            message.put("eliminatedPlayerId", eliminatedPlayerId);
            message.put("eliminatedPlayerName", eliminatedPlayerName);

            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
            log.info("투표 결과 업데이트 전송: {}", eliminatedPlayerId);
        } catch (Exception e) {
            log.error("투표 결과 업데이트 전송 실패: {}", game.getGameId(), e);
        }
    }

    public void endGame(String gameId, String winner) {
        Game game = games.get(gameId);
        if (game == null) return;

        game.setStatus(GameStatus.ENDED);
        game.setWinner(winner);
        game.setEndTime(LocalDateTime.now());
        gameTimerService.stopGameTimer(gameId);

        String winnerMessage = "MAFIA".equals(winner) ? "마피아의 승리!" : "시민의 승리!";
        Map<String, Object> gameEndMessage = new HashMap<>();
        gameEndMessage.put("type", "GAME_ENDED");
        gameEndMessage.put("gameId", gameId);
        gameEndMessage.put("roomId", game.getRoomId());
        gameEndMessage.put("winner", winner);
        gameEndMessage.put("message", winnerMessage);
        gameEndMessage.put("timestamp", LocalDateTime.now().toString());

        messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), gameEndMessage);
        log.info("게임 종료: {} - 승리자: {}", gameId, winner);
    }

    public String checkGameEnd(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;

        Map<String, Long> aliveCounts = game.getPlayers().countAliveRoles();
        long aliveMafia = aliveCounts.getOrDefault("MAFIA", 0L);
        long aliveCitizens = aliveCounts.getOrDefault("CITIZEN_TEAM", 0L);

        if (aliveMafia >= aliveCitizens && aliveCitizens > 0) {
            return "MAFIA";
        }
        if (aliveMafia == 0) {
            return "CITIZEN";
        }
        return null; // 게임 계속
    }

    public Game getGame(String gameId) {
        return games.get(gameId);
    }

    public Game switchPhase(String gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            log.error("게임을 찾을 수 없습니다: {}", gameId);
            return null;
        }

        switch (game.getGamePhase()) {
            case DAY_DISCUSSION:
                game.setGamePhase(GamePhase.DAY_VOTING);
                game.setRemainingTime(30);
                break;
            case DAY_VOTING:
                game.setGamePhase(GamePhase.DAY_FINAL_DEFENSE);
                game.setRemainingTime(10);
                break;
            case DAY_FINAL_DEFENSE:
                game.setGamePhase(GamePhase.DAY_FINAL_VOTE);
                game.setRemainingTime(15);
                break;
            case DAY_FINAL_VOTE:
                game.setGamePhase(GamePhase.NIGHT_ACTION);
                game.setIsDay(false);
                game.setRemainingTime(30);
                break;
            case NIGHT_ACTION:
                game.setCurrentPhase(game.getCurrentPhase() + 1);
                game.setGamePhase(GamePhase.DAY_DISCUSSION);
                for (GamePlayer player : game.getPlayers().getAsList()) {
                    game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
                }
                game.setIsDay(true);
                game.setRemainingTime(60);
                break;
        }
        game.setPhaseStartTime(LocalDateTime.now());
        return game;
    }

    public void deleteGame(String gameId) {
        games.remove(gameId);
        log.info("게임 삭제됨: {}", gameId);
    }
}