package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.*;

import com.example.mafiagame.game.repository.GameRepository;
import com.example.mafiagame.game.repository.GameStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameService {

    private final GameRepository gameRepository;
    private final GameStateRepository gameStateRepository;
    private final SimpMessagingTemplate messagingTemplate;
    @Lazy
    private final TimerService timerService;

    // --- Game Logic (Persistence + Redis) ---

    @Transactional
    public Game createGame(String roomId, List<GamePlayer> playerList) {
        String gameId = "game_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);

        // 1. MySQL: 이력 저장용 Entity 생성 (기본 정보만)
        Game game = Game.builder()
                .gameId(gameId)
                .roomId(roomId)
                .status(GameStatus.IN_PROGRESS)
                .maxPlayers(playerList.size())
                .startTime(LocalDateTime.now())
                .build();

        // Players mapping for DB (Historical record)
        List<GamePlayer> dbPlayers = playerList.stream().map(p -> {
            GamePlayer newP = GamePlayer.builder()
                    .user(p.getUser())
                    .isHost(p.isHost())
                    .role(p.getRole()) // Role might be null here, assigned later
                    .isAlive(true)
                    .build();
            newP.setGame(game);
            return newP;
        }).collect(Collectors.toList());

        game.setPlayers(dbPlayers);
        gameRepository.save(game); // 영구 저장

        // 2. Redis: 실시간 게임 상태 생성
        // 주의: GamePlayer 객체를 Redis에 그대로 저장 (Serializable/Jackson)
        GameState gameState = GameState.builder()
                .gameId(gameId)
                .roomId(roomId)
                .status(GameStatus.IN_PROGRESS)
                .gamePhase(GamePhase.DAY_DISCUSSION)
                .currentPhase(1)
                .players(new ArrayList<>(playerList)) // 복사해서 저장
                .build();
        gameStateRepository.save(gameState);

        // log.info("게임 생성됨: {}", gameId);
        return game;
    }

    public void assignRoles(String gameId) {
        // Redis에서 상태 가져오기
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            return;

        List<GamePlayer> players = gameState.getPlayers();
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

            // 개인 메시지 전송
            sendRoleAssignmentMessage(player.getPlayerId(), assignedRole);
        }

        // 변경된 역할 정보 Redis 저장
        gameStateRepository.save(gameState);

        // DB에도 역할 정보 업데이트하고 싶다면 여기서 GameRepository 호출 필요 (선택사항)
    }

    public void startGame(String gameId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            throw new RuntimeException("게임을 찾을 수 없습니다: " + gameId);

        // 1. Redis 상태 업데이트
        gameState.setStatus(GameStatus.IN_PROGRESS);
        toNextDayPhase(gameState); // 1일차 낮 시작 및 저장됨

        // 2. 타이머 시작
        timerService.startTimer(gameId);
    }

    @Transactional
    public void endGame(String gameId, String winner) {
        // 1. Redis 상태 조회
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            return; // 이미 종료된 게임일 수 있음

        // 2. MySQL 업데이트 (종료 시간, 승자)
        gameRepository.findById(gameId).ifPresent(game -> {
            game.setStatus(GameStatus.ENDED);
            game.setWinner(winner);
            game.setEndTime(LocalDateTime.now());
            gameRepository.save(game);
        });

        // 3. 타이머 중지
        timerService.stopTimer(gameId);

        // 4. 종료 메시지 전송
        messagingTemplate.convertAndSend("/topic/room." + gameState.getRoomId(),
                Map.of("type", "GAME_ENDED", "winner", winner, "players", gameState.getPlayers()));

        // 5. Redis 데이터 삭제 (게임 끝!)
        gameStateRepository.delete(gameId);
    }

    // --- Runtime Logic (Operates on GameState) ---

    public void advancePhase(String gameId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getStatus() != GameStatus.IN_PROGRESS)
            return;

        switch (gameState.getGamePhase()) {
            case DAY_DISCUSSION -> toPhase(gameState, GamePhase.DAY_VOTING, 60);
            case DAY_VOTING -> processDayVoting(gameState);
            case DAY_FINAL_DEFENSE -> toPhase(gameState, GamePhase.DAY_FINAL_VOTING, 15);
            case DAY_FINAL_VOTING -> processFinalVoting(gameState);
            case NIGHT_ACTION -> processNight(gameState);
        }

        // 상태 변경 후 저장 및 알림
        gameStateRepository.save(gameState);
        sendPhaseSwitchMessage(gameState);
    }

    public void vote(String gameId, String voterId, String targetId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_VOTING)
            return;

        if (findActivePlayerById(gameState, voterId) == null)
            return;

        gameState.getVotes().removeIf(v -> v.getVoterId().equals(voterId));
        gameState.getVotes().add(Vote.builder().voterId(voterId).targetId(targetId).build());

        gameStateRepository.save(gameState);
    }

    public void finalVote(String gameId, String voterId, String voteChoice) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_FINAL_VOTING)
            return;

        // 변론자 본인은 투표 불가
        if (findActivePlayerById(gameState, voterId) == null || voterId.equals(gameState.getVotedPlayerId()))
            return;

        gameState.getFinalVotes().removeIf(v -> v.getVoterId().equals(voterId));
        gameState.getFinalVotes().add(FinalVote.builder().voterId(voterId).vote(voteChoice).build());

        gameStateRepository.save(gameState);
    }

    public void nightAction(String gameId, String actorId, String targetId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.NIGHT_ACTION)
            return;

        GamePlayer actor = findActivePlayerById(gameState, actorId);
        if (actor == null || actor.getRole() == PlayerRole.CITIZEN)
            return;

        gameState.getNightActions().removeIf(action -> action.getActorId().equals(actorId));
        gameState.getNightActions().add(NightAction.builder().actorId(actorId).targetId(targetId).build());

        // 경찰은 즉시 결과 확인
        if (actor.getRole() == PlayerRole.POLICE) {
            GamePlayer target = findPlayerById(gameState, targetId);
            if (target != null)
                sendPoliceInvestigationResult(actor, target);
        }

        gameStateRepository.save(gameState);
    }

    public boolean updateTime(String gameId, String playerId, int seconds) {
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            return false;

        if (gameState.getGamePhase() != GamePhase.DAY_DISCUSSION) {
            return false;
        }

        // 시간 조절: PhaseEndTime을 앞당기거나 뒤로 미룸
        if (gameState.getPhaseEndTime() != null) {
            gameState.setPhaseEndTime(gameState.getPhaseEndTime().plusSeconds(seconds));
        }

        gameStateRepository.save(gameState);

        // 변경된 시간 브로드캐스트
        sendTimerUpdate(gameState);

        GamePlayer player = findPlayerById(gameState, playerId);
        if (player != null) {
            sendSystemMessage(gameState.getRoomId(), String.format("%s님이 시간을 %d초 %s했습니다.",
                    player.getPlayerName(), Math.abs(seconds), seconds > 0 ? "연장" : "단축"));
        }
        return true;
    }

    // --- Helper Methods ---

    private void toPhase(GameState gameState, GamePhase phase, int durationSeconds) {
        gameState.setGamePhase(phase);
        gameState.setPhaseEndTime(java.time.Instant.now().plusSeconds(durationSeconds));
        // 저장 로직은 호출자가 수행 (batch save)
    }

    private void toNextDayPhase(GameState gameState) {
        gameState.setCurrentPhase(gameState.getCurrentPhase() + 1);
        toPhase(gameState, GamePhase.DAY_DISCUSSION, 60);
        gameState.getNightActions().clear();
        gameStateRepository.save(gameState);
    }

    private void toNightPhase(GameState gameState) {
        toPhase(gameState, GamePhase.NIGHT_ACTION, 15);
        gameState.getVotes().clear();
        gameState.getFinalVotes().clear();
        gameState.setVotedPlayerId(null);
        // 저장 로직은 호출자가 수행
    }

    private void processDayVoting(GameState gameState) {
        List<String> topVotedIds = getTopVotedPlayers(gameState.getVotes());
        if (topVotedIds.size() != 1) {
            sendSystemMessage(gameState.getRoomId(), "투표가 무효 처리되어 밤으로 넘어갑니다.");
            toNightPhase(gameState);
        } else {
            String votedId = topVotedIds.get(0);
            gameState.setVotedPlayerId(votedId);
            toPhase(gameState, GamePhase.DAY_FINAL_DEFENSE, 60);

            findPlayerById(gameState, votedId, player -> sendSystemMessage(gameState.getRoomId(),
                    String.format("투표 결과 %s님이 최다 득표자가 되었습니다. 최후 변론을 시작합니다.", player.getPlayerName())));
        }
        gameState.getVotes().clear();
    }

    private void processFinalVoting(GameState gameState) {
        long agree = gameState.getFinalVotes().stream().filter(v -> "AGREE".equals(v.getVote())).count();
        long disagree = gameState.getFinalVotes().stream().filter(v -> "DISAGREE".equals(v.getVote())).count();

        if (agree > disagree) {
            findPlayerById(gameState, gameState.getVotedPlayerId(), player -> {
                player.setAlive(false);
                sendSystemMessage(gameState.getRoomId(),
                        String.format("최종 투표 결과, %s님이 처형되었습니다.", player.getPlayerName()));
            });
        }

        if (checkGameEnd(gameState))
            return;
        toNightPhase(gameState);
    }

    private void processNight(GameState gameState) {
        processNightActions(gameState);
        if (checkGameEnd(gameState))
            return;
        toNextDayPhase(gameState);
    }

    private void processNightActions(GameState gameState) {
        Map<String, Long> mafiaVotes = gameState.getNightActions().stream()
                .filter(action -> {
                    GamePlayer p = findPlayerById(gameState, action.getActorId());
                    return p != null && p.getRole() == PlayerRole.MAFIA;
                })
                .collect(Collectors.groupingBy(NightAction::getTargetId, Collectors.counting()));

        String mafiaTargetId = getTopVotedPlayers(mafiaVotes).stream().findFirst().orElse(null);

        String doctorTargetId = gameState.getNightActions().stream()
                .filter(action -> {
                    GamePlayer p = findPlayerById(gameState, action.getActorId());
                    return p != null && p.getRole() == PlayerRole.DOCTOR;
                })
                .map(NightAction::getTargetId).findFirst().orElse(null);

        if (mafiaTargetId != null && !mafiaTargetId.equals(doctorTargetId)) {
            findPlayerById(gameState, mafiaTargetId, killed -> {
                killed.setAlive(false);
                sendSystemMessage(gameState.getRoomId(), "지난 밤, " + killed.getPlayerName() + "님이 마피아의 공격으로 사망했습니다.");
            });
        } else {
            sendSystemMessage(gameState.getRoomId(), "지난 밤, 아무 일도 일어나지 않았습니다.");
        }
    }

    private boolean checkGameEnd(GameState gameState) {
        long mafia = gameState.getPlayers().stream().filter(p -> p.isAlive() && p.getRole() == PlayerRole.MAFIA)
                .count();
        long citizen = gameState.getPlayers().stream().filter(p -> p.isAlive() && p.getRole() != PlayerRole.MAFIA)
                .count();

        if (mafia >= citizen) {
            endGame(gameState.getGameId(), "MAFIA");
            return true;
        }
        if (mafia == 0) {
            endGame(gameState.getGameId(), "CITIZEN");
            return true;
        }
        return false;
    }

    // --- Utility Methods ---

    public GameState getGameState(String gameId) {
        return gameStateRepository.findById(gameId).orElse(null);
    }

    // 단순 조회용 (Controller 등에서 필요 시) - 이제 JPA Repository 사용
    public Game getGame(String gameId) {
        return gameRepository.findById(gameId).orElse(null);
    }

    public Game getGameByRoomId(String roomId) {
        // 주의: DB에서 가져오므로 실시간 상태가 아님.
        // Active Game을 찾으려면 Redis를 뒤져야 하는데, Keys * 는 성능 이슈.
        // 편의상 DB에서 Status가 IN_PROGRESS인 것을 찾음.
        return gameRepository.findByRoomIdAndStatus(roomId, GameStatus.IN_PROGRESS).orElse(null);
    }

    // 사용되지 않을 수 있으나 호환성 유지
    public Set<String> getActiveGameIds() {
        // Redis Keys scan 필요 (추후 구현 or 비권장)
        return Collections.emptySet();
    }

    private List<String> getTopVotedPlayers(List<Vote> votes) {
        if (votes == null || votes.isEmpty())
            return new ArrayList<>();
        Map<String, Long> counts = votes.stream()
                .collect(Collectors.groupingBy(Vote::getTargetId, Collectors.counting()));
        if (counts.isEmpty())
            return new ArrayList<>();
        long max = Collections.max(counts.values());
        return counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
    }

    // 오버로딩: Map 입력
    private List<String> getTopVotedPlayers(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty())
            return new ArrayList<>();
        long max = Collections.max(counts.values());
        return counts.entrySet().stream().filter(e -> e.getValue() == max).map(Map.Entry::getKey).toList();
    }

    private GamePlayer findActivePlayerById(GameState gameState, String playerId) {
        GamePlayer p = findPlayerById(gameState, playerId);
        return (p != null && p.isAlive()) ? p : null;
    }

    private GamePlayer findPlayerById(GameState gameState, String playerId) {
        if (gameState.getPlayers() == null)
            return null;
        return gameState.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst().orElse(null);
    }

    private void findPlayerById(GameState gameState, String playerId, java.util.function.Consumer<GamePlayer> action) {
        GamePlayer p = findPlayerById(gameState, playerId);
        if (p != null)
            action.accept(p);
    }

    // --- Message Sending ---

    public void sendTimerUpdate(GameState gameState) {
        messagingTemplate.convertAndSend("/topic/room." + gameState.getRoomId(),
                Map.of("type", "TIMER_UPDATE",
                        "gameId", gameState.getGameId(),
                        "phaseEndTime",
                        gameState.getPhaseEndTime() != null ? gameState.getPhaseEndTime().toString() : "",
                        "gamePhase", gameState.getGamePhase(),
                        "currentPhase", gameState.getCurrentPhase()));
    }

    private void sendPhaseSwitchMessage(GameState gameState) {
        messagingTemplate.convertAndSend("/topic/room." + gameState.getRoomId(),
                Map.of("type", "PHASE_SWITCHED", "game", gameState)); // 이제 GameState를 보내야 함!
    }

    private void sendSystemMessage(String roomId, String content) {
        messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of("type", "SYSTEM", "content", content));
    }

    private void sendRoleAssignmentMessage(String playerId, PlayerRole role) {
        try {
            sendPrivateMessage(playerId, Map.of(
                    "type", "ROLE_ASSIGNED",
                    "role", role.name(),
                    "roleDescription", getRoleDescription(role)));
        } catch (Exception e) {
            log.error("Role assign fail: {}", playerId, e);
        }
    }

    private void sendPoliceInvestigationResult(GamePlayer police, GamePlayer target) {
        sendPrivateMessage(police.getPlayerId(), Map.of(
                "type", "PRIVATE_MESSAGE",
                "messageType", "POLICE_INVESTIGATION",
                "content", String.format("경찰 조사 결과: %s님은 [ %s ] 입니다.",
                        target.getPlayerName(), target.getRole() == PlayerRole.MAFIA ? "마피아" : "시민")));
    }

    private void sendPrivateMessage(String playerId, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSend("/topic/private/" + playerId, payload);
        } catch (Exception e) {
            log.error("Private msg fail", e);
        }
    }

    private String getRoleDescription(PlayerRole role) {
        return switch (role) {
            case MAFIA -> "밤에 한 명을 지목하여 제거할 수 있습니다.";
            case POLICE -> "밤에 한 명을 지목하여 마피아인지 확인할 수 있습니다.";
            case DOCTOR -> "밤에 한 명을 지목하여 마피아의 공격으로부터 보호할 수 있습니다.";
            case CITIZEN -> "특별한 능력이 없습니다. 추리를 통해 마피아를 찾아내세요.";
        };
    }
}
