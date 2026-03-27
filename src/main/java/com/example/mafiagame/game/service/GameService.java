package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.entity.Game;
import com.example.mafiagame.game.domain.entity.GamePlayer;
import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.domain.state.PlayerRole;
import com.example.mafiagame.game.domain.state.Team;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.game.state.GamePhaseFactory;
import com.example.mafiagame.game.state.GamePhaseState;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;

import java.util.concurrent.TimeUnit;

import com.example.mafiagame.game.repository.GameRepository;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.global.error.ErrorCode;

import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.example.mafiagame.chat.service.WebSocketMessageBroadcaster;

import java.time.LocalDateTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Random;

import java.util.stream.Collectors;

@Service
@Slf4j
public class GameService {

    private final GameRepository gameRepository;
    private final GameStateRepository gameStateRepository;
    private final UsersRepository userRepository;
    private final WebSocketMessageBroadcaster messageBroadcaster;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final TransactionTemplate transactionTemplate;

    private final RedisTimerService timerService;
    private final GamePhaseFactory gamePhaseFactory;
    private final PhaseResultProcessor phaseResultProcessor;

    private final RedisTemplate<String, ChatRoom> chatRoomRedisTemplate;

    public GameService(
            GameRepository gameRepository,
            GameStateRepository gameStateRepository,
            UsersRepository userRepository,
            WebSocketMessageBroadcaster messageBroadcaster,
            StringRedisTemplate stringRedisTemplate,
            RedissonClient redissonClient,
            TransactionTemplate transactionTemplate,
            RedisTimerService timerService,
            GamePhaseFactory gamePhaseFactory,
            PhaseResultProcessor phaseResultProcessor,
            RedisTemplate<String, ChatRoom> chatRoomRedisTemplate) {
        this.gameRepository = gameRepository;
        this.gameStateRepository = gameStateRepository;
        this.userRepository = userRepository;
        this.messageBroadcaster = messageBroadcaster;
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.transactionTemplate = transactionTemplate;
        this.timerService = timerService;
        this.gamePhaseFactory = gamePhaseFactory;
        this.phaseResultProcessor = phaseResultProcessor;
        this.chatRoomRedisTemplate = chatRoomRedisTemplate;
    }

    private static final String ROOM_KEY_PREFIX = "chatroom:";
    private static final String VOTE_KEY_PREFIX = "game:votes:";
    private static final String FINAL_VOTE_KEY_PREFIX = "game:finalvotes:";
    private static final String NIGHT_ACTION_KEY_PREFIX = "game:nightactions:";
    private static final String GAME_META_KEY_PREFIX = "game:meta:";
    private static final String GAME_CREATE_LOCK_PREFIX = "lock:game:create:";
    private static final String GAME_END_LOCK_PREFIX = "lock:game:end:";
    private static final String GAME_ADVANCE_LOCK_PREFIX = "lock:game:advance:";
    private static final String GAME_ACTION_LOCK_PREFIX = "lock:game:action:";

    @Transactional
    public GameState createGame(String roomId) {
        // 분산 락을 사용하여 동시 게임 생성 방지
        String lockKey = GAME_CREATE_LOCK_PREFIX + roomId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 5초 동안 락 획득 시도, 락 획득 시 10초간 유지
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[게임 생성] 락 획득 실패: roomId={}", roomId);
                // 이미 게임이 생성 중이므로 기존 게임 반환 시도
                GameState existingGame = getActiveGameByRoomId(roomId);
                if (existingGame != null) {
                    return existingGame;
                }
                throw ErrorCode.GAME_CREATE_IN_PROGRESS.commonException();
            }

            // ChatRoom 정보 Redis에서 조회 (Source of Truth)
            ChatRoom chatRoom = chatRoomRedisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomId);
            if (chatRoom == null) {
                throw ErrorCode.ROOM_NOT_FOUND.commonException();
            }

            // 중복 게임 생성 방지: 이미 진행 중인 게임이 있는지 확인
            if (hasActiveGame(roomId)) {
                log.warn("[게임 생성] 이미 진행 중인 게임이 있습니다: roomId={}", roomId);
                GameState existingGame = getActiveGameByRoomId(roomId);
                if (existingGame != null) {
                    return existingGame;
                }
            }

            List<ChatUser> participants = chatRoom.getParticipants();

            // 참가자 수 검증
            if (participants.size() < 4) {
                throw ErrorCode.INSUFFICIENT_PLAYERS.commonException();
            }

            String gameId = "game_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);

            List<String> playerIds = participants.stream()
                    .map(ChatUser::getUserId)
                    .toList();
            Map<String, Users> userMap = userRepository.findAllByUserLoginIdIn(playerIds)
                    .stream()
                    .collect(Collectors.toMap(Users::getUserLoginId, u -> u));

            // MySQL: 이력 저장용 Entity 생성
            Game game = Game.builder()
                    .gameId(gameId)
                    .roomId(roomId)
                    .status(GameStatus.IN_PROGRESS)
                    .startTime(LocalDateTime.now())
                    .build();

            List<GamePlayer> dbPlayers = participants.stream().map(participant -> {
                Users user = userMap.get(participant.getUserId());
                if (user == null) {
                    throw ErrorCode.USER_NOT_FOUND.commonException();
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
            // ChatUser -> GamePlayerState 변환 (factory method 활용)
            List<GamePlayerState> gamePlayers = participants.stream()
                    .map(participant -> {
                        Users user = userMap.get(participant.getUserId());
                        return GamePlayerState.builder()
                                .playerId(user.getUserLoginId())
                                .playerName(user.getNickname())
                                .isAlive(true)
                                .build();
                    })
                    .toList();

            GameState gameState = GameState.builder()
                    .gameId(gameId)
                    .roomId(roomId)
                    .roomName(chatRoom.getRoomName()) // Redis에서 조회한 방 이름 사용
                    .status(GameStatus.IN_PROGRESS)
                    .gamePhase(GamePhase.NIGHT_ACTION)
                    .currentPhase(0)
                    .players(gamePlayers)
                    .build();

            gameStateRepository.save(gameState);

            assignRoles(gameId);
            startGame(gameId);

            GameState updatedGameState = gameStateRepository.findById(gameId)
                    .orElseThrow(ErrorCode.GAMESTATE_NOT_FOUND::commonException);

            messageBroadcaster.sendGameStart(roomId, updatedGameState);
            log.info("게임 생성됨: {}", gameId);
            return updatedGameState;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ErrorCode.GAME_CREATE_INTERRUPTED.commonException();
        } finally {
            // 락 해제
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private void assignRoles(String gameId) {
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

    /**
     * Initialize the game's first phase (day discussion), persist the initial
     * GameState, and start the phase timer.
     *
     * Sets the game status to in-progress, sets the current phase to 1 with
     * GamePhase.DAY_DISCUSSION, clears any pending night actions,
     * persists the updated GameState, and enqueues the Redis-backed timer for the phase
     * end.
     *
     * @param gameId the identifier of the game to start
     * @throws RuntimeException if the game state for the given `gameId` cannot be
     *                          found
     */
    private void startGame(String gameId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null)
            throw ErrorCode.GAMESTATE_NOT_FOUND.commonException();

        // 1. Redis 상태 업데이트
        gameState.setStatus(GameStatus.IN_PROGRESS);
        gameState.setCurrentPhase(1);
        gameState.setGamePhase(GamePhase.NIGHT_ACTION);
        gameState.setPhaseEndTime(System.currentTimeMillis() + (15 * 1000L)); // 밤행동 15초
        gameState.getNightActions().clear();
        gameStateRepository.save(gameState);

        // [AI] 첫 페이즈 추천 문구 생성 - 비활성화 (API 할당량 제한, 채팅 기반 호출만 사용)
        // suggestionService.generateAiSuggestionsAsync(gameId,
        // GamePhase.DAY_DISCUSSION);

        // 2. 타이머 시작
        timerService.startTimer(gameState);
    }

    public void endGame(String gameId, Team winnerTeam) {
        // [수정] 게임 상태 변경은 하나의 통합된 락(GAME_ADVANCE_LOCK)을 사용하는 것이 안전합니다.
        // endGame이 advancePhase 내부에서 호출되든, 외부(강제종료 등)에서 호출되든 동일한 자원을 보호해야 하기 때문입니다.
        String lockKey = GAME_ADVANCE_LOCK_PREFIX + gameId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도 (이미 advancePhase에서 획득한 경우 재진입 가능)
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[endGame] 락 획득 실패: gameId={}", gameId);
                return;
            }

            transactionTemplate.execute(status -> {
                // 1단계: 상태 검증
                GameState gameState = getGameState(gameId);
                if (gameState == null) {
                    log.info("[endGame] 이미 삭제된 게임 상태: gameId={}", gameId);
                    return null;
                }

                Game game = gameRepository.findById(gameId)
                        .orElseThrow(ErrorCode.GAME_NOT_FOUND::commonException);
                if (game.getStatus() == GameStatus.ENDED) {
                    log.info("[endGame] 이미 종료 처리된 게임: gameId={}", gameId);
                    return null;
                }

                // 2단계: DB 데이터 원자적 업데이트
                game.endGame(winnerTeam);
                gameRepository.save(game); // 명시적 저장 (변경 감지로도 되지만 명확성 위해)

                // [최적화] N개의 쿼리를 2개의 배치 쿼리로 개선 (승리팀/패배팀)
                List<String> winners = new ArrayList<>();
                List<String> losers = new ArrayList<>();

                boolean isCitizenWin = winnerTeam == Team.CITIZEN;
                for (GamePlayerState gp : gameState.getPlayers()) {
                    if (gp.getPlayerId() == null)
                        continue;
                    boolean isMafia = gp.getTeam() == Team.MAFIA;
                    boolean isWin = (isCitizenWin && !isMafia) || (!isCitizenWin && isMafia);

                    if (isWin)
                        winners.add(gp.getPlayerId());
                    else
                        losers.add(gp.getPlayerId());
                }

                if (!winners.isEmpty())
                    userRepository.updateStatsBatch(winners, true);
                if (!losers.isEmpty())
                    userRepository.updateStatsBatch(losers, false);

                // 3단계: 부수 효과 (Redis 삭제, 타이머 중지, 메시지 전송)
                // DB 트랜잭션이 성공적으로 커밋된 후에만 실행되도록 보장
                String roomId = gameState.getRoomId();
                List<GamePlayerState> playersSnapshot = new ArrayList<>(gameState.getPlayers());

                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            finalizeGameEnd(gameId, roomId, winnerTeam, playersSnapshot);
                        }
                    });
                } else {
                    // 트랜잭션 비활성 환경(테스트 등)에서는 즉시 실행
                    finalizeGameEnd(gameId, roomId, winnerTeam, playersSnapshot);
                }

                return null;
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[endGame] 락 획득 중 인터럽트 발생: gameId={}", gameId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 게임 종료 후 부수 효과 처리 (Redis 삭제, 타이머 중지, 알림 발송)
     */
    private void finalizeGameEnd(String gameId, String roomId, Team winnerTeam, List<GamePlayerState> players) {
        try {
            timerService.stopTimer(gameId);
            messageBroadcaster.sendGameEnded(roomId, winnerTeam, players);
            gameStateRepository.delete(gameId);
            log.info("[endGame] 게임 종료 처리 완료: gameId={}, winner={}", gameId, winnerTeam);
        } catch (Exception e) {
            log.error("[endGame] 후속 처리 중 오류 발생 (DB는 커밋됨): gameId={}", gameId, e);
        }
    }

    /**
     * Advance the game's state machine: process the current phase's results,
     * transition to and process the next phase, persist the updated state,
     * broadcast the phase switch, and start the phase timer.
     *
     * @param gameId the identifier of the game to advance; if the game is not found
     *               or not in progress this method is a no-op
     */
    public boolean advancePhase(String gameId) {
        String lockKey = GAME_ADVANCE_LOCK_PREFIX + gameId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[advancePhase] 락 획득 실패: gameId={}", gameId);
                return false;
            }

            GameState gameState = getGameState(gameId);
            if (gameState == null || gameState.getStatus() != GameStatus.IN_PROGRESS)
                return true;

            // State Pattern: 현재 페이즈 상태 객체
            GamePhaseState currentState = gamePhaseFactory.getState(gameState.getGamePhase());

            // 1단계: 현재 페이즈 결과 처리 (State Pattern의 onExit에 위임)
            currentState.onExit(gameState, phaseResultProcessor);

            // 게임이 종료되었으면 endGame 호출 후 리턴
            Team winnerTeam = phaseResultProcessor.getWinnerIfGameEnded(gameState);
            if (winnerTeam != null) {
                gameState.setStatus(GameStatus.ENDED);
                gameStateRepository.save(gameState);
                endGame(gameId, winnerTeam);
                return true;
            }

            // 2단계: 다음 상태로 전환 (State Pattern이 전환 책임)
            GamePhaseState nextState = currentState.nextState(gameState);
            nextState.process(gameState);

            // 3단계: 페이즈 종료 시간 설정 + 저장 + 타이머 시작
            gameState.setPhaseEndTime(System.currentTimeMillis() + (nextState.getDurationSeconds() * 1000L));
            gameStateRepository.save(gameState);
            sendPhaseSwitchMessage(gameState);
            timerService.startTimer(gameState);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[advancePhase] 락 획득 중 인터럽트: gameId={}", gameId, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 투표 (Redis Hash 저장)
     */
    public void vote(String gameId, String voterId, String targetId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_VOTING)
            return;

        if (findActivePlayerById(gameState, voterId) == null)
            return;

        String votesKey = VOTE_KEY_PREFIX + gameId;
        try {
            boolean stored = putHashIfPhaseMatches(
                    gameId,
                    votesKey,
                    GamePhase.DAY_VOTING,
                    gameState.getCurrentPhase(),
                    voterId,
                    targetId);
            if (stored) {
                log.debug("[투표] 성공: gameId={}, voterId={}, targetId={}", gameId, voterId, targetId);
            } else {
                log.warn("[투표] 무시됨(페이즈 변경/메타 없음): gameId={}, voterId={}", gameId, voterId);
            }
        } catch (Exception e) {
            log.error("[투표] 오류: gameId={}", gameId, e);
        }
    }

    /**
     * 최종 투표 (Redis Hash 저장)
     */
    public void finalVote(String gameId, String voterId, String voteChoice) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_FINAL_VOTING)
            return;

        if (!checkFinalVoteValidity(gameState, voterId))
            return;

        String finalVotesKey = FINAL_VOTE_KEY_PREFIX + gameId;
        try {
            boolean stored = putHashIfPhaseMatches(
                    gameId,
                    finalVotesKey,
                    GamePhase.DAY_FINAL_VOTING,
                    gameState.getCurrentPhase(),
                    voterId,
                    voteChoice);
            if (stored) {
                log.debug("[최종투표] 성공: gameId={}, voterId={}, choice={}", gameId, voterId, voteChoice);
            } else {
                log.warn("[최종투표] 무시됨(페이즈 변경/메타 없음): gameId={}, voterId={}", gameId, voterId);
            }
        } catch (Exception e) {
            log.error("[최종투표] 오류: gameId={}", gameId, e);
        }
    }

    private boolean checkFinalVoteValidity(GameState gameState, String voterId) {
        if (findActivePlayerById(gameState, voterId) == null || voterId.equals(gameState.getVotedPlayerId())) {
            return false;
        }
        return true;
    }

    /**
     * 밤 행동 (Redis Hash 저장)
     */
    public void nightAction(String gameId, String actorId, String targetId) {
        GameState gameState = getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.NIGHT_ACTION)
            return;

        GamePlayerState actor = findActivePlayerById(gameState, actorId);
        if (actor == null || actor.getRole() == PlayerRole.CITIZEN)
            return;

        String nightActionsKey = NIGHT_ACTION_KEY_PREFIX + gameId;
        try {
            boolean stored = putHashIfPhaseMatches(
                    gameId,
                    nightActionsKey,
                    GamePhase.NIGHT_ACTION,
                    gameState.getCurrentPhase(),
                    actorId,
                    targetId);
            if (stored) {
                log.debug("[밤행동] 성공: gameId={}, actorId={}, targetId={}", gameId, actorId, targetId);

                // 경찰은 즉시 결과 확인
                if (actor.getRole() == PlayerRole.POLICE) {
                    GamePlayerState target = findPlayerById(gameState, targetId);
                    if (target != null)
                        sendPoliceInvestigationResult(actor, target);
                }
            } else {
                log.warn("[밤행동] 무시됨(페이즈 변경/메타 없음): gameId={}, actorId={}", gameId, actorId);
            }
        } catch (Exception e) {
            log.error("[밤행동] 오류: gameId={}", gameId, e);
        }
    }

    /**
     * Adjusts the remaining time of the current day discussion phase for a game.
     *
     * <p>
     * If the game does not exist or is not in the DAY_DISCUSSION phase, no change
     * is made.
     * </p>
     *
     * @param gameId   the id of the game to modify
     * @param playerId the id of the player requesting the time change (used for
     *                 notification)
     * @param seconds  number of seconds to adjust the phase end time; positive to
     *                 extend, negative to shorten
     * @return `true` if the phase time was updated, `false` if the game was not
     *         found or not in the day discussion phase
     */
    public boolean updateTime(String gameId, String playerId, int seconds) {
        String lockKey = GAME_ADVANCE_LOCK_PREFIX + gameId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[updateTime] 락 획득 실패: gameId={}", gameId);
                return false;
            }

            GameState gameState = getGameState(gameId);
            if (gameState == null)
                return false;

            if (gameState.getGamePhase() != GamePhase.DAY_DISCUSSION) {
                return false;
            }

            if (gameState.getPhaseEndTime() != null) {
                gameState.setPhaseEndTime(gameState.getPhaseEndTime() + (seconds * 1000L));
            }

            gameStateRepository.save(gameState);
            sendTimerUpdate(gameState);
            timerService.startTimer(gameState);

            GamePlayerState player = findPlayerById(gameState, playerId);
            if (player != null) {
                sendSystemMessage(gameState.getRoomId(), String.format("%s님이 시간을 %d초 %s했습니다.",
                        player.getPlayerName(), Math.abs(seconds), seconds > 0 ? "연장" : "단축"));
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[updateTime] 락 획득 중 인터럽트: gameId={}", gameId, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // --- Query Methods ---

    public GameState getGameState(String gameId) {
        return gameStateRepository.findById(gameId).orElse(null);
    }

    // 단순 조회용 (Controller 등에서 필요 시) - 이제 JPA Repository 사용
    public Game getGame(String gameId) {
        return gameRepository.findById(gameId).orElse(null);
    }

    /**
     * 방 ID로 게임 조회
     */
    public Game getGameByRoomId(String roomId) {
        // 가장 최근 진행 중인 게임 반환
        return gameRepository.findFirstByRoomIdAndStatusOrderByStartTimeDesc(roomId, GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    public GameState getGameByPlayerId(String playerId) {
        return gameStateRepository.findByPlayerId(playerId).orElse(null);
    }

    private GamePlayerState findActivePlayerById(GameState gameState, String playerId) {
        return gameState.findActivePlayer(playerId);
    }

    private boolean hasActiveGame(String roomId) {
        return getActiveGameByRoomId(roomId) != null;
    }

    private GameState getActiveGameByRoomId(String roomId) {
        return gameStateRepository.findByRoomId(roomId)
                .filter(gs -> gs.getStatus() == GameStatus.IN_PROGRESS)
                .orElse(null);
    }

    private GamePlayerState findPlayerById(GameState gameState, String playerId) {
        return gameState.findPlayer(playerId);
    }

    /**
     * 페이즈 일치 확인 후 Hash에 값을 저장 (Redisson Lock으로 원자성 보장)
     */
    private boolean putHashIfPhaseMatches(String gameId, String hashKey, GamePhase expectedPhase,
            int expectedCurrentPhase, String field, String value) {
        String lockKey = GAME_ACTION_LOCK_PREFIX + gameId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(3, 5, java.util.concurrent.TimeUnit.SECONDS)) {
                log.warn("[putHashIfPhaseMatches] 락 획득 실패: gameId={}", gameId);
                return false;
            }

            String metaKey = GAME_META_KEY_PREFIX + gameId;
            Object phase = stringRedisTemplate.opsForHash().get(metaKey, "phase");
            Object current = stringRedisTemplate.opsForHash().get(metaKey, "currentPhase");
            Object status = stringRedisTemplate.opsForHash().get(metaKey, "status");

            if (phase == null || current == null || status == null) {
                return false;
            }

            if (!expectedPhase.name().equals(phase.toString())
                    || !String.valueOf(expectedCurrentPhase).equals(current.toString())
                    || !"IN_PROGRESS".equals(status.toString())) {
                return false;
            }

            stringRedisTemplate.opsForHash().put(hashKey, field, value);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[putHashIfPhaseMatches] 락 획득 중 인터럽트: gameId={}", gameId, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
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
        log.info("[경찰조사] 결과 전송: police={}, target={}, isMafia={}",
                police.getPlayerId(), target.getPlayerName(), target.getRole() == PlayerRole.MAFIA);
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
