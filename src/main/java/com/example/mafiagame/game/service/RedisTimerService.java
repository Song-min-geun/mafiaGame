package com.example.mafiagame.game.service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.repository.GameTimerRepository;
import com.example.mafiagame.game.timer.GameTimerJob;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RedisTimerService {

    private static final String GAME_ADVANCE_LOCK_PREFIX = "lock:game:advance:";

    private final GameStateRepository gameStateRepository;
    private final GameTimerRepository gameTimerRepository;
    private final RedissonClient redissonClient;

    public RedisTimerService(
            GameStateRepository gameStateRepository,
            GameTimerRepository gameTimerRepository,
            RedissonClient redissonClient) {
        this.gameStateRepository = gameStateRepository;
        this.gameTimerRepository = gameTimerRepository;
        this.redissonClient = redissonClient;
    }

    /**
     * 개별 게임마다 JVM 스케줄러를 두지 않고, Redis ZSET 대기열에 타이머를 등록한다.
     */
    public void startTimer(GameState gameState) {
        if (gameState == null || gameState.getPhaseEndTime() == null || gameState.getPhaseEndTime() <= 0) {
            log.warn("타이머 시작 실패: 종료 시간이 유효하지 않음. gameId={}",
                    gameState != null ? gameState.getGameId() : null);
            return;
        }

        String timerToken = UUID.randomUUID().toString();
        GameTimerJob timerJob = new GameTimerJob(
                gameState.getGameId(),
                gameState.getGamePhase(),
                gameState.getCurrentPhase(),
                timerToken);

        gameTimerRepository.schedule(timerJob, gameState.getPhaseEndTime());
        log.info("Redis 타이머 등록됨: gameId={}, phase={}, currentPhase={}, endTime={}",
                gameState.getGameId(), gameState.getGamePhase(), gameState.getCurrentPhase(),
                gameState.getPhaseEndTime());
    }

    /**
     * 서버 재기동 복구용.
     * 같은 게임 단위 락 안에서 최신 상태 재조회, hasScheduledTimer 체크, 타이머 등록을 수행해
     * 클러스터 동시 기동 시에도 중복 복구를 방지한다.
     *
     * @return 실제로 누락된 타이머를 복구해 등록한 경우에만 true
     */
    public boolean recoverMissingTimer(GameState gameState) {
        if (gameState == null || gameState.getGameId() == null) {
            return false;
        }

        String gameId = gameState.getGameId();
        RLock lock = redissonClient.getLock(GAME_ADVANCE_LOCK_PREFIX + gameId);

        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("[recoverMissingTimer] 게임 락 획득 실패: gameId={}", gameId);
                return false;
            }

            GameState latestGameState = gameStateRepository.findById(gameId).orElse(null);
            if (latestGameState == null || latestGameState.getStatus() != GameStatus.IN_PROGRESS) {
                log.debug("[recoverMissingTimer] 복구 스킵: 게임이 없거나 진행 중이 아님. gameId={}, status={}", gameId,
                        latestGameState != null ? latestGameState.getStatus() : null);
                return false;
            }

            if (latestGameState.getPhaseEndTime() == null || latestGameState.getPhaseEndTime() <= 0) {
                log.warn("[recoverMissingTimer] 복구 스킵: 종료 시간이 유효하지 않음. gameId={}", gameId);
                return false;
            }

            if (gameTimerRepository.hasScheduledTimer(gameId)) {
                log.debug("[recoverMissingTimer] 복구 스킵: 이미 타이머가 등록됨. gameId={}", gameId);
                return false;
            }

            startTimer(latestGameState);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[recoverMissingTimer] 게임 락 획득 중 인터럽트: gameId={}", gameId, e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 테스트 및 복구용: 전달받은 종료 시각으로 Redis 타이머를 재등록한다.
     */
    public void startTimer(String gameId, long phaseEndTimeMillis) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null) {
            log.warn("타이머 시작 실패: 게임 상태가 없음. gameId={}", gameId);
            return;
        }

        gameState.setPhaseEndTime(phaseEndTimeMillis);
        gameStateRepository.save(gameState);
        startTimer(gameState);
    }

    /**
     * [Deprecated] 기존 호환용 - Redis에 저장된 종료 시간으로 재등록
     */
    @Deprecated
    public void startTimer(String gameId) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null || gameState.getPhaseEndTime() == null || gameState.getPhaseEndTime() <= 0) {
            log.warn("타이머 시작 실패: 게임이 없거나 종료 시간이 설정되지 않음. gameId={}", gameId);
            return;
        }
        startTimer(gameState);
    }

    public void stopTimer(String gameId) {
        gameTimerRepository.stop(gameId);
        log.info("Redis 타이머 제거됨: gameId={}", gameId);
    }
}
