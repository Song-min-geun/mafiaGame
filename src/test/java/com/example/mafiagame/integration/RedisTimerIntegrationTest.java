package com.example.mafiagame.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.repository.GameTimerRepository;
import com.example.mafiagame.game.service.GameTimerRecoveryService;
import com.example.mafiagame.game.service.RedisTimerService;
import com.example.mafiagame.support.RedisTestContainerSupport;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RedisTimerIntegrationTest extends RedisTestContainerSupport {

    private static final String WAITING_KEY = "game:timer:waiting";
    private static final String CURRENT_TIMER_KEY_PREFIX = "game:timer:current:";

    @Autowired
    private GameStateRepository gameStateRepository;

    @Autowired
    private GameTimerRepository gameTimerRepository;

    @Autowired
    private RedisTimerService redisTimerService;

    @Autowired
    private GameTimerRecoveryService gameTimerRecoveryService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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
}
