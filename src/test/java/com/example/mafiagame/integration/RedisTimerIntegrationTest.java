package com.example.mafiagame.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.repository.GameTimerRepository;
import com.example.mafiagame.game.service.GameTimerRecoveryService;
import com.example.mafiagame.game.service.RedisTimerService;
import com.example.mafiagame.game.timer.GameTimerJob;
import com.example.mafiagame.support.RedisTestContainerSupport;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RedisTimerIntegrationTest extends RedisTestContainerSupport {

    private static final String WAITING_KEY = "game:timer:waiting";
    private static final String PROCESSING_KEY = "game:timer:processing";
    private static final String CURRENT_TIMER_KEY_PREFIX = "game:timer:current:";
    private static final String META_KEY_PREFIX = "game:meta:";
    private static final String TIMER_TOKEN_FIELD = "timerToken";
    private static final long FUTURE_TIMER_OFFSET_MILLIS = 120_000L;

    @Autowired
    private GameStateRepository gameStateRepository;

    @Autowired
    private GameTimerRepository gameTimerRepository;

    @SpyBean
    private RedisTimerService redisTimerService;

    @Autowired
    private GameTimerRecoveryService gameTimerRecoveryService;

    @Autowired
    @Qualifier("coreStringRedisTemplate")
    private StringRedisTemplate stringRedisTemplate;

    @AfterEach
    void tearDown() {
        reset(redisTimerService);
        try (RedisConnection connection = stringRedisTemplate.getRequiredConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    @DisplayName("Redis 타이머 등록 시 메타 토큰과 ZSET 대기열이 함께 저장된다")
    void startTimerPersistsMetaAndQueueState() {
        GameState gameState = timedGameState("game-timer-start", System.currentTimeMillis() + 60_000L);
        gameStateRepository.save(gameState);

        redisTimerService.startTimer(gameState);

        String currentTimer = stringRedisTemplate.opsForValue().get(currentTimerKey(gameState.getGameId()));
        assertThat(currentTimer).isNotBlank();
        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, currentTimer)).isNotNull();
        assertThat(gameStateRepository.findMeta(gameState.getGameId()))
                .get()
                .extracting(meta -> meta.timerToken())
                .isEqualTo(currentTimer.split("\\|", 4)[3]);

        redisTimerService.stopTimer(gameState.getGameId());

        assertThat(stringRedisTemplate.opsForValue().get(currentTimerKey(gameState.getGameId()))).isNull();
        assertThat(gameTimerRepository.hasScheduledTimer(gameState.getGameId())).isFalse();
        assertThat(gameStateRepository.findMeta(gameState.getGameId()))
                .get()
                .extracting(meta -> meta.timerToken())
                .isNull();
    }

    @Test
    @DisplayName("서버 재기동 복구 시 누락된 Redis 타이머를 다시 큐잉한다")
    void recoverTimersRequeuesMissingTimer() {
        GameState gameState = timedGameState("game-timer-recovery", System.currentTimeMillis() + 45_000L);
        gameStateRepository.save(gameState);

        assertThat(gameTimerRepository.hasScheduledTimer(gameState.getGameId())).isFalse();
        assertThat(gameStateRepository.findMeta(gameState.getGameId()))
                .get()
                .extracting(meta -> meta.timerToken())
                .isNull();

        gameTimerRecoveryService.recoverTimers();

        String currentTimer = stringRedisTemplate.opsForValue().get(currentTimerKey(gameState.getGameId()));
        assertThat(currentTimer).isNotBlank();
        assertThat(gameTimerRepository.hasScheduledTimer(gameState.getGameId())).isTrue();
        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, currentTimer)).isNotNull();
        assertThat(gameStateRepository.findMeta(gameState.getGameId()))
                .get()
                .extracting(meta -> meta.timerToken())
                .isEqualTo(currentTimer.split("\\|", 4)[3]);
    }

    @Test
    @DisplayName("동일 게임 복구가 동시에 실행되어도 하나의 누락 타이머만 복구된다")
    void recoverMissingTimerIsAtomicPerGame() throws Exception {
        GameState gameState = timedGameState("game-timer-race", System.currentTimeMillis() + 60_000L);
        gameStateRepository.save(gameState);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executorService.submit(() -> recoverAfterBarrier(gameState, ready, start));
            Future<Boolean> second = executorService.submit(() -> recoverAfterBarrier(gameState, ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Boolean> results = List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder(true, false);

            String currentTimer = stringRedisTemplate.opsForValue().get(currentTimerKey(gameState.getGameId()));
            assertThat(currentTimer).isNotBlank();
            assertThat(gameTimerRepository.hasScheduledTimer(gameState.getGameId())).isTrue();
            assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, currentTimer)).isNotNull();
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("하나의 게임 복구가 실패해도 나머지 게임 복구는 계속된다")
    void recoverTimersContinuesWhenSingleGameRecoveryFails() {
        GameState failingGame = timedGameState("game-timer-fail", System.currentTimeMillis() + 45_000L);
        GameState succeedingGame = timedGameState("game-timer-success", System.currentTimeMillis() + 45_000L);
        gameStateRepository.save(failingGame);
        gameStateRepository.save(succeedingGame);

        doAnswer(invocation -> {
            GameState state = invocation.getArgument(0);
            if (failingGame.getGameId().equals(state.getGameId())) {
                throw new IllegalStateException("simulated recovery failure");
            }
            return invocation.callRealMethod();
        }).when(redisTimerService).recoverMissingTimer(any(GameState.class));

        gameTimerRecoveryService.recoverTimers();

        assertThat(gameTimerRepository.hasScheduledTimer(failingGame.getGameId())).isFalse();
        assertThat(gameTimerRepository.hasScheduledTimer(succeedingGame.getGameId())).isTrue();
    }

    @Test
    @DisplayName("stale token 타이머는 processing lease 만료 후 재큐잉되지 않는다")
    void staleTokenTimerIsNotRequeuedAfterProcessingLeaseExpires() {
        String gameId = "game-timer-stale-token";
        long scheduledAt = futureTimerTime();
        long claimNow = scheduledAt + 1_000L;
        long leaseMillis = 5_000L;
        GameTimerJob staleTimer = timerJob(gameId, "stale-token");
        GameTimerJob currentTimer = timerJob(gameId, "current-token");

        gameTimerRepository.schedule(staleTimer, scheduledAt);
        assertThat(gameTimerRepository.claimDueTimers(claimNow, 10, leaseMillis)).containsExactly(staleTimer);

        replaceCurrentTimerWithoutCleaningProcessing(currentTimer, scheduledAt + 30_000L);

        assertThat(gameTimerRepository.claimExpiredProcessing(claimNow + leaseMillis + 1L, 10))
                .containsExactly(staleTimer);
        assertThat(gameTimerRepository.requeueIfCurrent(staleTimer, claimNow)).isFalse();

        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, staleTimer.toMember())).isNull();
        assertThat(stringRedisTemplate.opsForZSet().score(PROCESSING_KEY, staleTimer.toMember())).isNull();
        assertThat(stringRedisTemplate.opsForValue().get(currentTimerKey(gameId))).isEqualTo(currentTimer.toMember());
        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, currentTimer.toMember())).isNotNull();
    }

    @Test
    @DisplayName("processing lease가 만료된 current 타이머는 waiting 큐로 재등록된다")
    void expiredProcessingLeaseRequeuesCurrentTimer() {
        String gameId = "game-timer-processing-requeue";
        long scheduledAt = futureTimerTime();
        long claimNow = scheduledAt + 1_000L;
        long leaseMillis = 5_000L;
        long requeueAt = scheduledAt + 30_000L;
        GameTimerJob timerJob = timerJob(gameId, "lease-token");

        gameTimerRepository.schedule(timerJob, scheduledAt);

        assertThat(gameTimerRepository.claimDueTimers(claimNow, 10, leaseMillis)).containsExactly(timerJob);
        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, timerJob.toMember())).isNull();
        assertThat(stringRedisTemplate.opsForZSet().score(PROCESSING_KEY, timerJob.toMember()))
                .isEqualTo((double) (claimNow + leaseMillis));

        assertThat(gameTimerRepository.claimExpiredProcessing(claimNow + leaseMillis + 1L, 10))
                .containsExactly(timerJob);
        assertThat(gameTimerRepository.requeueIfCurrent(timerJob, requeueAt)).isTrue();

        assertThat(stringRedisTemplate.opsForValue().get(currentTimerKey(gameId))).isEqualTo(timerJob.toMember());
        assertThat(stringRedisTemplate.opsForZSet().score(PROCESSING_KEY, timerJob.toMember())).isNull();
        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, timerJob.toMember()))
                .isEqualTo((double) requeueAt);
    }

    @Test
    @DisplayName("동시에 due timer를 claim해도 하나의 worker만 같은 타이머를 획득한다")
    void concurrentClaimDueTimersMovesTimerOnlyOnce() throws Exception {
        String gameId = "game-timer-concurrent-claim";
        long scheduledAt = futureTimerTime();
        long claimNow = scheduledAt + 1_000L;
        GameTimerJob timerJob = timerJob(gameId, "claim-token");

        gameTimerRepository.schedule(timerJob, scheduledAt);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<List<GameTimerJob>> first = executorService.submit(() -> claimAfterBarrier(ready, start, claimNow));
            Future<List<GameTimerJob>> second = executorService.submit(() -> claimAfterBarrier(ready, start, claimNow));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<List<GameTimerJob>> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS));
            assertThat(results).anySatisfy(result -> assertThat(result).containsExactly(timerJob));
            assertThat(results).anySatisfy(result -> assertThat(result).isEmpty());

            assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, timerJob.toMember())).isNull();
            assertThat(stringRedisTemplate.opsForZSet().score(PROCESSING_KEY, timerJob.toMember())).isNotNull();
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("이전 타이머 ACK는 새 current timer를 삭제하지 않는다")
    void ackForPreviousTimerDoesNotDeleteNewCurrentTimer() {
        String gameId = "game-timer-ack-keeps-current";
        long scheduledAt = futureTimerTime();
        long claimNow = scheduledAt + 1_000L;
        GameTimerJob previousTimer = timerJob(gameId, "previous-token");
        GameTimerJob currentTimer = timerJob(gameId, "current-token");

        gameTimerRepository.schedule(previousTimer, scheduledAt);
        assertThat(gameTimerRepository.claimDueTimers(claimNow, 10, 5_000L)).containsExactly(previousTimer);
        gameTimerRepository.schedule(currentTimer, scheduledAt + 30_000L);

        gameTimerRepository.ack(previousTimer);

        assertThat(stringRedisTemplate.opsForValue().get(currentTimerKey(gameId))).isEqualTo(currentTimer.toMember());
        assertThat(stringRedisTemplate.opsForHash().get(metaKey(gameId), TIMER_TOKEN_FIELD))
                .isEqualTo(currentTimer.timerToken());
        assertThat(stringRedisTemplate.opsForZSet().score(WAITING_KEY, currentTimer.toMember())).isNotNull();
        assertThat(gameTimerRepository.hasScheduledTimer(gameId)).isTrue();
    }

    private GameState timedGameState(String gameId, long phaseEndTime) {
        return GameState.builder()
                .gameId(gameId)
                .roomId("room-" + gameId)
                .roomName("room-" + gameId)
                .status(GameStatus.IN_PROGRESS)
                .gamePhase(GamePhase.NIGHT_ACTION)
                .currentPhase(1)
                .phaseEndTime(phaseEndTime)
                .build();
    }

    private String currentTimerKey(String gameId) {
        return CURRENT_TIMER_KEY_PREFIX + gameId;
    }

    private String metaKey(String gameId) {
        return META_KEY_PREFIX + gameId;
    }

    private long futureTimerTime() {
        return System.currentTimeMillis() + FUTURE_TIMER_OFFSET_MILLIS;
    }

    private GameTimerJob timerJob(String gameId, String timerToken) {
        return new GameTimerJob(gameId, GamePhase.NIGHT_ACTION, 1, timerToken);
    }

    private void replaceCurrentTimerWithoutCleaningProcessing(GameTimerJob timerJob, long executeAtMillis) {
        stringRedisTemplate.opsForValue().set(currentTimerKey(timerJob.gameId()), timerJob.toMember());
        stringRedisTemplate.opsForHash().put(metaKey(timerJob.gameId()), TIMER_TOKEN_FIELD, timerJob.timerToken());
        stringRedisTemplate.opsForZSet().add(WAITING_KEY, timerJob.toMember(), executeAtMillis);
    }

    private boolean recoverAfterBarrier(GameState gameState, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start signal not received");
        }
        return redisTimerService.recoverMissingTimer(gameState);
    }

    private List<GameTimerJob> claimAfterBarrier(CountDownLatch ready, CountDownLatch start, long claimNow)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("start signal not received");
        }
        return gameTimerRepository.claimDueTimers(claimNow, 10, 5_000L);
    }
}
