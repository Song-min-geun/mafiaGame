package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameStatus;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.domain.Team;
import com.example.mafiagame.game.domain.Vote;
import com.example.mafiagame.game.domain.FinalVote;
import com.example.mafiagame.game.domain.NightAction;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.chat.service.ChatRoomService;
import com.example.mafiagame.game.strategy.RoleActionFactory;
import com.example.mafiagame.game.strategy.RoleActionStrategy;
import com.example.mafiagame.game.strategy.NightActionResult;
import com.example.mafiagame.game.state.GamePhaseFactory;
import com.example.mafiagame.game.state.GamePhaseState;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.repository.UserRepository;
import com.example.mafiagame.game.repository.GameRepository;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.global.error.ErrorCode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Lazy;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.mafiagame.chat.service.WebSocketMessageBroadcaster;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameService {

    private final GameRepository gameRepository;
    private final GameStateRepository gameStateRepository;
    private final UserRepository userRepository;
    private final WebSocketMessageBroadcaster messageBroadcaster;
    private final StringRedisTemplate stringRedisTemplate;
    @Lazy
    private final TimerService timerService;
    private final RoleActionFactory roleActionFactory;
    private final GamePhaseFactory gamePhaseFactory;
    private final ChatRoomService chatRoomService;

    private static final String GAME_STATE_KEY_PREFIX = "game:state:";
    private static final String VOTE_KEY_PREFIX = "game:votes:";
    private static final String FINAL_VOTE_KEY_PREFIX = "game:finalvotes:";
    private static final String NIGHT_ACTION_KEY_PREFIX = "game:nightactions:";

    private static final String VOTE_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local votesKey = KEYS[2]
            local voterId = ARGV[1]
            local targetId = ARGV[2]

            -- 게임 상태 존재 확인
            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            -- 투표 저장 (Hash: voterId -> targetId)
            redis.call('HSET', votesKey, voterId, targetId)
            redis.call('EXPIRE', votesKey, 1800)

            return 'OK'
            """;

    private static final String FINAL_VOTE_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local finalVotesKey = KEYS[2]
            local voterId = ARGV[1]
            local voteChoice = ARGV[2]

            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            redis.call('HSET', finalVotesKey, voterId, voteChoice)
            redis.call('EXPIRE', finalVotesKey, 1800)

            return 'OK'
            """;

    private static final String NIGHT_ACTION_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local nightActionsKey = KEYS[2]
            local actorId = ARGV[1]
            local targetId = ARGV[2]

            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            redis.call('HSET', nightActionsKey, actorId, targetId)
            redis.call('EXPIRE', nightActionsKey, 1800)

            return 'OK'
            """;

    @Transactional
    public GameState createGame(String roomId) {
        // 중복 게임 생성 방지: 이미 진행 중인 게임이 있는지 확인
        if (hasActiveGame(roomId)) {
            log.warn("[게임 생성] 이미 진행 중인 게임이 있습니다: roomId={}", roomId);
            GameState existingGame = getActiveGameByRoomId(roomId);
            if (existingGame != null) {
                return existingGame;
            }
        }

        // ChatRoom에서 플레이어 정보 가져오기
        ChatRoom chatRoom = chatRoomService.getRoom(roomId);
        if (chatRoom == null) {
            throw new RuntimeException("채팅방을 찾을 수 없습니다: " + roomId);
        }

        List<ChatUser> participants = chatRoom.getParticipants();
        if (participants.size() < 4) {
            throw new RuntimeException("게임을 시작하려면 최소 4명이 필요합니다.");
        }

        String gameId = "game_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);

        List<String> playerIds = participants.stream()
                .map(ChatUser::getUserId)
                .toList();
        Map<String, User> userMap = userRepository.findAllByUserLoginIdIn(playerIds)
                .stream()
                .collect(Collectors.toMap(User::getUserLoginId, u -> u));

        // MySQL: 이력 저장용 Entity 생성
        Game game = Game.builder()
                .gameId(gameId)
                .roomId(roomId)
                .status(GameStatus.IN_PROGRESS)
                .startTime(LocalDateTime.now())
                .build();

        List<GamePlayer> dbPlayers = participants.stream().map(participant -> {
            User user = userMap.get(participant.getUserId());
            if (user == null) {
                throw new RuntimeException("User not found: " + participant.getUserId());
            }
            return GamePlayer.builder()
                    .game(game)
                    .user(user)
                    .isAlive(true)
                    .build();
        }).collect(Collectors.toList());

        game.setPlayers(dbPlayers);
        gameRepository.save(game);

        // Redis: 게임 상태 저장
        GameState gameState = GameState.builder()
                .gameId(gameId)
                .roomId(roomId)
                .roomName(chatRoom.getRoomName())
                .status(GameStatus.IN_PROGRESS)
                .gamePhase(GamePhase.NIGHT_ACTION)
                .currentPhase(0)
                .players(participants.stream().map(participant -> {
                    User user = userMap.get(participant.getUserId());
                    return GamePlayerState.builder()
                            .playerId(user.getUserLoginId())
                            .playerName(user.getNickname())
                            .isAlive(true)
                            .voteCount(0)
                            .team(null)
                            .targetPlayerId(null)
                            .build();
                }).collect(Collectors.toList()))
                .build();

        gameStateRepository.save(gameState);

        assignRoles(gameId);
        startGame(gameId);

        GameState updatedGameState = gameStateRepository.findById(gameId)
                .orElseThrow(ErrorCode.GAMESTATE_NOT_FOUND::commonException);

        messageBroadcaster.sendGameStart(roomId, updatedGameState);
        log.info("게임 생성됨: {}", gameId);
        return gameState;
    }

    public void assignRoles(String gameId) {
        // Redis에서 상태 가져오기
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            return;

        List<GamePlayerState> players = gameState.getPlayers();
        int playerCount = players.size();
        int mafiaCount = Math.max(1, playerCount / 4);
        List<PlayerRole> roles = new ArrayList<>();

        for (int i = 0; i < mafiaCount; i++) {
            roles.add(PlayerRole.MAFIA);
        }
        roles.add(PlayerRole.DOCTOR);
        roles.add(PlayerRole.POLICE);
        while (roles.size() < playerCount) {
            roles.add(PlayerRole.CITIZEN);
        }
        Collections.shuffle(roles);

        for (int i = 0; i < playerCount; i++) {
            GamePlayerState player = players.get(i);
            PlayerRole assignedRole = roles.get(i);
            player.setRole(assignedRole);

            // 개인 메시지 전송
            sendRoleAssignmentMessage(player.getPlayerId(), assignedRole);
        }

        for (int i = 0; i < playerCount; i++) {
            GamePlayerState player = players.get(i);

            if (player.getRole() == PlayerRole.MAFIA) {
                player.setTeam(Team.MAFIA);
            } else if (player.getRole() == PlayerRole.DOCTOR || player.getRole() == PlayerRole.POLICE
                    || player.getRole() == PlayerRole.CITIZEN) {
                player.setTeam(Team.CITIZEN);
            } else {
                player.setTeam(Team.NEUTRALITY);
            }
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

        // [AI] 첫 페이즈 추천 문구 생성 - 비활성화 (API 할당량 제한, 채팅 기반 호출만 사용)
        // suggestionService.generateAiSuggestionsAsync(gameId,
        // GamePhase.DAY_DISCUSSION);

        // 2. 타이머 시작
        timerService.startTimer(gameId);
    }

    @Transactional
    public void endGame(String gameId, String winner) {
        // 1. Redis 상태 조회
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            return; // 이미 종료된 게임일 수 있음

        // 2. MySQL 업데이트 (종료 시간, 승자, 전적)
        gameRepository.findById(gameId).ifPresent(game -> {
            game.setStatus(GameStatus.ENDED);
            game.setEndTime(LocalDateTime.now());
            gameRepository.save(game);
        });

        List<String> playerIds = gameState.getPlayers().stream()
                .map(GamePlayerState::getPlayerId)
                .filter(id -> id != null)
                .toList();

        List<User> users = userRepository.findAllByUserLoginIdIn(playerIds);
        Map<String, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getUserLoginId, u -> u));

        // 각 플레이어의 전적 업데이트
        boolean isCitizenWin = "CITIZEN".equals(winner);
        for (GamePlayerState gp : gameState.getPlayers()) {
            if (gp.getPlayerId() == null)
                continue;

            User user = userMap.get(gp.getPlayerId());
            if (user == null)
                continue;

            user.incrementPlayCount();
            boolean isMafia = (gp.getTeam() == Team.MAFIA);

            // 승리 조건: 시민팀 승리 & !마피아 OR 마피아팀 승리 & 마피아
            if ((isCitizenWin && !isMafia) || (!isCitizenWin && isMafia)) {
                user.incrementWinCount();
            }

            // 승률 업데이트
            user.updateWinRate();
        }

        userRepository.saveAll(users);

        // 3. 타이머 중지
        timerService.stopTimer(gameId);

        // 4. 종료 메시지 전송
        messageBroadcaster.sendGameEnded(gameState.getRoomId(), winner, gameState.getPlayers());

        // 5. Redis 데이터 삭제 (게임 끝!)
        gameStateRepository.delete(gameId);
    }

    public void advancePhase(String gameId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getStatus() != GameStatus.IN_PROGRESS)
            return;

        // State Pattern: 페이즈별 상태 객체가 처리
        GamePhaseState currentState = gamePhaseFactory.getState(gameState.getGamePhase());

        // 현재 페이즈 처리 (투표 결과, 밤 행동 등)
        processCurrentPhase(gameState);

        // 게임이 종료되었으면 리턴
        if (gameState.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }

        // 다음 상태로 전환
        GamePhaseState nextState = currentState.nextState(gameState);
        nextState.process(gameState);

        // 페이즈 종료 시간 설정 (epoch millis)
        gameState.setPhaseEndTime(System.currentTimeMillis() + (nextState.getDurationSeconds() * 1000L));

        // 상태 변경 후 저장 및 알림
        gameStateRepository.save(gameState);
        sendPhaseSwitchMessage(gameState);

        // [AI] 다음 페이즈 문구 미리 생성 - 비활성화 (API 할당량 제한, 채팅 기반 호출만 사용)
        // suggestionService.generateAiSuggestionsAsync(gameId,
        // currentState.nextState(gameState).getGamePhase());

        // 다음 페이즈 타이머 시작
        timerService.startTimer(gameId);
    }

    private void processCurrentPhase(GameState gameState) {
        switch (gameState.getGamePhase()) {
            case DAY_VOTING -> processDayVoting(gameState);
            case DAY_FINAL_VOTING -> processFinalVoting(gameState);
            case NIGHT_ACTION -> processNight(gameState);
            default -> {
            } // DAY_DISCUSSION, DAY_FINAL_DEFENSE는 특별한 처리 없음
        }
    }

    // ... (vote, finalVote etc methods omitted) ...

    private boolean checkGameEnd(GameState gameState) {
        long mafia = gameState.getPlayers().stream().filter(p -> p.isAlive() && p.getRole() == PlayerRole.MAFIA)
                .count();
        long citizen = gameState.getPlayers().stream().filter(p -> p.isAlive() && p.getRole() != PlayerRole.MAFIA)
                .count();

        if (mafia >= citizen) {
            gameState.setStatus(GameStatus.ENDED); // Mark object as ended
            endGame(gameState.getGameId(), "MAFIA");
            return true;
        }
        if (mafia == 0) {
            gameState.setStatus(GameStatus.ENDED); // Mark object as ended
            endGame(gameState.getGameId(), "CITIZEN");
            return true;
        }
        return false;
    }

    /**
     * 투표 (Lua Script 기반 원자적 처리)
     * 
     * 동시에 여러 플레이어가 투표해도 데이터 유실 없이 처리됩니다.
     */
    public void vote(String gameId, String voterId, String targetId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_VOTING)
            return;

        if (findActivePlayerById(gameState, voterId) == null)
            return;

        // Lua Script로 원자적 투표 저장
        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String votesKey = VOTE_KEY_PREFIX + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(VOTE_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            String result = stringRedisTemplate.execute(script, List.of(gameStateKey, votesKey), voterId, targetId);
            if ("OK".equals(result)) {
                log.debug("[투표] 성공: gameId={}, voterId={}, targetId={}", gameId, voterId, targetId);
            } else {
                log.warn("[투표] 실패: gameId={}, result={}", gameId, result);
            }
        } catch (Exception e) {
            log.error("[투표] Lua Script 오류: gameId={}", gameId, e);
        }
    }

    /**
     * 최종 투표 (Lua Script 기반 원자적 처리)
     */
    public void finalVote(String gameId, String voterId, String voteChoice) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_FINAL_VOTING)
            return;

        // 변론자 본인은 투표 불가
        if (findActivePlayerById(gameState, voterId) == null || voterId.equals(gameState.getVotedPlayerId()))
            return;

        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String finalVotesKey = FINAL_VOTE_KEY_PREFIX + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(FINAL_VOTE_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            String result = stringRedisTemplate.execute(script, List.of(gameStateKey, finalVotesKey), voterId,
                    voteChoice);
            if ("OK".equals(result)) {
                log.debug("[최종투표] 성공: gameId={}, voterId={}, choice={}", gameId, voterId, voteChoice);
            } else {
                log.warn("[최종투표] 실패: gameId={}, result={}", gameId, result);
            }
        } catch (Exception e) {
            log.error("[최종투표] Lua Script 오류: gameId={}", gameId, e);
        }
    }

    /**
     * 밤 행동 (Lua Script 기반 원자적 처리)
     */
    public void nightAction(String gameId, String actorId, String targetId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.NIGHT_ACTION)
            return;

        GamePlayerState actor = findActivePlayerById(gameState, actorId);
        if (actor == null || actor.getRole() == PlayerRole.CITIZEN)
            return;

        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String nightActionsKey = NIGHT_ACTION_KEY_PREFIX + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(NIGHT_ACTION_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            String result = stringRedisTemplate.execute(script, List.of(gameStateKey, nightActionsKey), actorId,
                    targetId);
            if ("OK".equals(result)) {
                log.debug("[밤행동] 성공: gameId={}, actorId={}, targetId={}", gameId, actorId, targetId);

                // 경찰은 즉시 결과 확인
                if (actor.getRole() == PlayerRole.POLICE) {
                    GamePlayerState target = findPlayerById(gameState, targetId);
                    if (target != null)
                        sendPoliceInvestigationResult(actor, target);
                }
            } else {
                log.warn("[밤행동] 실패: gameId={}, result={}", gameId, result);
            }
        } catch (Exception e) {
            log.error("[밤행동] Lua Script 오류: gameId={}", gameId, e);
        }
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
            gameState.setPhaseEndTime(gameState.getPhaseEndTime() + (seconds * 1000L));
        }

        gameStateRepository.save(gameState);

        // 변경된 시간 브로드캐스트
        sendTimerUpdate(gameState);

        // 타이머 재설정 (기존 타이머 취소 후 새 시간으로 예약)
        timerService.startTimer(gameId);

        GamePlayerState player = findPlayerById(gameState, playerId);
        if (player != null) {
            sendSystemMessage(gameState.getRoomId(), String.format("%s님이 시간을 %d초 %s했습니다.",
                    player.getPlayerName(), Math.abs(seconds), seconds > 0 ? "연장" : "단축"));
        }
        return true;
    }

    // --- Helper Methods ---

    private void toPhase(GameState gameState, GamePhase phase, int durationSeconds) {
        gameState.setGamePhase(phase);
        gameState.setPhaseEndTime(System.currentTimeMillis() + (durationSeconds * 1000L));
        // 저장 로직은 호출자가 수행 (batch save)
    }

    private void toNextDayPhase(GameState gameState) {
        gameState.setCurrentPhase(gameState.getCurrentPhase() + 1);
        toPhase(gameState, GamePhase.DAY_DISCUSSION, 60);
        gameState.getNightActions().clear();
        gameStateRepository.save(gameState);
    }

    private void toNightPhase(GameState gameState) {
        toPhase(gameState, GamePhase.NIGHT_ACTION, 30); // 밤 30초
        gameState.getVotes().clear();
        gameState.getFinalVotes().clear();
        gameState.setVotedPlayerId(null);

        // 이전 밤의 데이터가 남아있을 수 있으므로 확실히 초기화
        gameState.getNightActions().clear();
        clearNightActionsFromRedis(gameState.getGameId());

        // 저장 로직은 호출자가 수행
    }

    private void processDayVoting(GameState gameState) {
        // Redis Hash에서 투표 동기화
        syncVotesFromRedis(gameState);

        List<String> topVotedIds = getTopVotedPlayers(gameState.getVotes());
        if (topVotedIds.size() != 1) {
            sendSystemMessage(gameState.getRoomId(), "투표가 무효 처리되어 밤으로 넘어갑니다.");
            toNightPhase(gameState);
        } else {
            String votedId = topVotedIds.get(0);
            gameState.setVotedPlayerId(votedId);
            toPhase(gameState, GamePhase.DAY_FINAL_DEFENSE, 20); // 최후 변론 45초

            findPlayerById(gameState, votedId, player -> sendSystemMessage(gameState.getRoomId(),
                    String.format("투표 결과 %s님이 최다 득표자가 되었습니다. 최후 변론을 시작합니다.", player.getPlayerName())));
        }
        gameState.getVotes().clear();
        clearVotesFromRedis(gameState.getGameId());
    }

    private void processFinalVoting(GameState gameState) {
        // Redis Hash에서 최종 투표 동기화
        syncFinalVotesFromRedis(gameState);

        long agree = gameState.getFinalVotes().stream().filter(v -> "AGREE".equals(v.getVote())).count();
        long disagree = gameState.getFinalVotes().stream().filter(v -> "DISAGREE".equals(v.getVote())).count();

        if (agree > disagree) {
            findPlayerById(gameState, gameState.getVotedPlayerId(), player -> {
                player.setAlive(false);
                sendSystemMessage(gameState.getRoomId(),
                        String.format("최종 투표 결과, %s님이 처형되었습니다.", player.getPlayerName()));
            });
        }
        clearFinalVotesFromRedis(gameState.getGameId());

        if (checkGameEnd(gameState))
            return;
        toNightPhase(gameState);
    }

    private void processNight(GameState gameState) {
        // Redis Hash에서 밤 행동 동기화
        syncNightActionsFromRedis(gameState);

        processNightActions(gameState);
        clearNightActionsFromRedis(gameState.getGameId());

        if (checkGameEnd(gameState))
            return;
        toNextDayPhase(gameState);
    }

    // ========== Redis Hash → GameState 동기화 메서드 ==========

    private void syncVotesFromRedis(GameState gameState) {
        String votesKey = VOTE_KEY_PREFIX + gameState.getGameId();
        try {
            var votes = stringRedisTemplate.opsForHash().entries(votesKey);
            gameState.getVotes().clear();
            votes.forEach((voterId, targetId) -> gameState.getVotes().add(Vote.builder()
                    .voterId((String) voterId)
                    .targetId((String) targetId)
                    .build()));
            log.debug("[동기화] 투표 {}건 로드: gameId={}", votes.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 투표 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    private void syncFinalVotesFromRedis(GameState gameState) {
        String finalVotesKey = FINAL_VOTE_KEY_PREFIX + gameState.getGameId();
        try {
            var votes = stringRedisTemplate.opsForHash().entries(finalVotesKey);
            gameState.getFinalVotes().clear();
            votes.forEach((voterId, voteChoice) -> gameState.getFinalVotes().add(FinalVote.builder()
                    .voterId((String) voterId)
                    .vote((String) voteChoice)
                    .build()));
            log.debug("[동기화] 최종투표 {}건 로드: gameId={}", votes.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 최종투표 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    private void syncNightActionsFromRedis(GameState gameState) {
        String nightActionsKey = NIGHT_ACTION_KEY_PREFIX + gameState.getGameId();
        try {
            var actions = stringRedisTemplate.opsForHash().entries(nightActionsKey);
            gameState.getNightActions().clear();
            actions.forEach((actorId, targetId) -> gameState.getNightActions().add(NightAction.builder()
                    .actorId((String) actorId)
                    .targetId((String) targetId)
                    .build()));
            log.debug("[동기화] 밤행동 {}건 로드: gameId={}", actions.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 밤행동 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    private void clearVotesFromRedis(String gameId) {
        stringRedisTemplate.delete(VOTE_KEY_PREFIX + gameId);
    }

    private void clearFinalVotesFromRedis(String gameId) {
        stringRedisTemplate.delete(FINAL_VOTE_KEY_PREFIX + gameId);
    }

    private void clearNightActionsFromRedis(String gameId) {
        stringRedisTemplate.delete(NIGHT_ACTION_KEY_PREFIX + gameId);
    }

    private void processNightActions(GameState gameState) {
        // Strategy Pattern: 역할에 맞는 전략을 Factory에서 가져와 실행
        List<NightActionResult> results = new ArrayList<>();

        for (NightAction action : gameState.getNightActions()) {
            GamePlayerState actor = findPlayerById(gameState, action.getActorId());
            GamePlayerState target = findPlayerById(gameState, action.getTargetId());

            if (actor == null || target == null)
                continue;

            RoleActionStrategy strategy = roleActionFactory.getStrategy(actor.getRole());
            if (strategy != null) {
                NightActionResult result = strategy.execute(gameState, actor, target);
                results.add(result);

                // 경찰은 즉시 결과 전송
                if (actor.getRole() == PlayerRole.POLICE) {
                    sendPoliceInvestigationResult(actor, target);
                }
            }
        }

        // 마피아 공격 결과 계산
        String mafiaTargetId = results.stream()
                .filter(r -> r.getActorRole() == PlayerRole.MAFIA)
                .collect(Collectors.groupingBy(NightActionResult::getTargetId, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // 의사 보호 대상 확인
        String doctorTargetId = results.stream()
                .filter(r -> r.getActorRole() == PlayerRole.DOCTOR)
                .map(NightActionResult::getTargetId)
                .findFirst()
                .orElse(null);

        // 결과 처리
        if (mafiaTargetId != null && !mafiaTargetId.equals(doctorTargetId)) {
            findPlayerById(gameState, mafiaTargetId, killed -> {
                killed.setAlive(false);
                sendSystemMessage(gameState.getRoomId(), "지난 밤, " + killed.getPlayerName() + "님이 마피아의 공격으로 사망했습니다.");
            });
        } else {
            sendSystemMessage(gameState.getRoomId(), "지난 밤, 아무 일도 일어나지 않았습니다.");
        }
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
        // 가장 최근 진행 중인 게임 반환
        return gameRepository.findFirstByRoomIdAndStatusOrderByStartTimeDesc(roomId, GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    /**
     * 유저가 참여 중인 게임 조회 (Redis)
     */
    public GameState getGameByPlayerId(String playerId) {
        return gameStateRepository.findByPlayerId(playerId).orElse(null);
    }

    /**
     * 해당 방에 이미 진행 중인 게임이 있는지 확인
     */
    public boolean hasActiveGame(String roomId) {
        return getActiveGameByRoomId(roomId) != null;
    }

    /**
     * 해당 방의 진행 중인 게임 상태 조회 (Redis)
     */
    public GameState getActiveGameByRoomId(String roomId) {
        return gameStateRepository.findByRoomId(roomId)
                .filter(gs -> gs.getStatus() == GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    // 사용되지 않을 수 있으나 호환성 유지
    public Set<String> getActiveGameIds() {
        // Redis Keys scan 필요 (추후 구현 or 비권장)
        return Collections.emptySet();
    }

    /**
     * 플레이어가 방을 떠날 수 있는지 확인
     * (게임 진행 중 살아있는 플레이어는 퇴장 불가)
     */
    public boolean canPlayerLeaveRoom(String roomId, String userId) {
        Game game = getGameByRoomId(roomId);
        if (game == null)
            return true;

        GameState gameState = getGameState(game.getGameId());
        if (gameState == null)
            return true;

        return gameState.canPlayerLeave(userId);
    }

    /**
     * 플레이어가 채팅할 수 있는지 확인
     */
    public boolean canPlayerChat(String roomId, String playerId) {
        Game game = getGameByRoomId(roomId);
        if (game == null)
            return true;

        GameState gameState = getGameState(game.getGameId());
        if (gameState == null)
            return true;

        return gameState.canPlayerChat(playerId);
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

    private GamePlayerState findActivePlayerById(GameState gameState, String playerId) {
        GamePlayerState p = findPlayerById(gameState, playerId);
        return (p != null && p.isAlive()) ? p : null;
    }

    private GamePlayerState findPlayerById(GameState gameState, String playerId) {
        if (gameState.getPlayers() == null)
            return null;
        return gameState.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst().orElse(null);
    }

    private void findPlayerById(GameState gameState, String playerId,
            java.util.function.Consumer<GamePlayerState> action) {
        GamePlayerState p = findPlayerById(gameState, playerId);
        if (p != null)
            action.accept(p);
    }

    // --- Message Sending ---

    public void sendTimerUpdate(GameState gameState) {
        messageBroadcaster.broadcastToRoom(gameState.getRoomId(),
                Map.of("type", "TIMER_UPDATE",
                        "gameId", gameState.getGameId(),
                        "phaseEndTime",
                        gameState.getPhaseEndTime() != null ? gameState.getPhaseEndTime().toString() : "",
                        "gamePhase", gameState.getGamePhase(),
                        "currentPhase", gameState.getCurrentPhase()));
    }

    private void sendPhaseSwitchMessage(GameState gameState) {
        messageBroadcaster.sendPhaseChange(gameState.getRoomId(), gameState);
    }

    private void sendSystemMessage(String roomId, String content) {
        messageBroadcaster.sendSystemMessage(roomId, content);
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

    private void sendPoliceInvestigationResult(GamePlayerState police, GamePlayerState target) {
        sendPrivateMessage(police.getPlayerId(), Map.of(
                "type", "PRIVATE_MESSAGE",
                "messageType", "POLICE_INVESTIGATION",
                "content", String.format("경찰 조사 결과: %s님은 [ %s ] 입니다.",
                        target.getPlayerName(), target.getRole() == PlayerRole.MAFIA ? "마피아" : "시민")));
    }

    private void sendPrivateMessage(String playerId, Map<String, Object> payload) {
        messageBroadcaster.sendToUser(playerId, payload);
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