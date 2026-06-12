package com.example.mafiagame.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
// import org.springframework.stereotype.Service;  // 현재 RedisTimerService와 충돌 방지를 위해 비활성화

import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.repository.GameStateRepository;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * [레퍼런스 코드] Spring TaskScheduler 기반의 JVM 인메모리 타이머 서비스.
 *
 * <p>
 * 이 클래스는 Redis ZSET Worker 방식으로 마이그레이션되기 전에 사용했던
 * 원래의 타이머 구현입니다. 각 게임마다 {@link ConcurrentHashMap}으로
 * {@link ScheduledFuture}를 관리하며, {@link TaskScheduler}를 통해
 * {@code phaseEndTime}에 한 번 실행되는 콜백을 예약합니다.
 * </p>
 *
 * <h3>장점</h3>
 * <ul>
 * <li>Redis 의존성 없이 단일 JVM에서 동작</li>
 * <li>구현이 간단하고 디버깅이 용이</li>
 * </ul>
 *
 * <h3>단점 (마이그레이션 이유)</h3>
 * <ul>
 * <li>서버 재시작 시 모든 타이머 유실</li>
 * <li>수평 확장(Scale-out) 시 동일 게임의 타이머가 여러 인스턴스에 중복 등록 가능</li>
 * <li>JVM 메모리에 의존하므로 게임 수 증가 시 메모리 부담</li>
 * </ul>
 *
 * <p>
 * 현재는 {@link RedisTimerService} + {@link GameTimerWorker}로 대체되었습니다.
 * 이 파일은 비교 참조 및 아키텍처 변경 이력 보존 목적으로 유지됩니다.
 * </p>
 *
 * @see RedisTimerService
 * @see GameTimerWorker
 */
@Slf4j
// @Service // 비활성화: 현재 RedisTimerService가 타이머 역할을 수행
public class SchedulerTimerService {

    private final TaskScheduler taskScheduler;
    private final GameService gameService;
    private final GameStateRepository gameStateRepository;

    /** 게임별 예약된 작업을 관리하는 맵 (GameId -> ScheduledFuture) */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public SchedulerTimerService(TaskScheduler taskScheduler, @Lazy GameService gameService,
            GameStateRepository gameStateRepository) {
        this.taskScheduler = taskScheduler;
        this.gameService = gameService;
        this.gameStateRepository = gameStateRepository;
    }

    /**
     * 타이머 시작 (phaseEndTime 직접 전달 - Race Condition 방지).
     *
     * <p>
     * 기존 타이머가 있으면 취소한 뒤, 지정된 {@code phaseEndTimeMillis}에
     * {@link GameService#advancePhase(String)}를 호출하는 콜백을 예약한다.
     * </p>
     *
     * @param gameId             게임 ID
     * @param phaseEndTimeMillis 페이즈 종료 시간 (epoch millis)
     */
    public void startTimer(String gameId, long phaseEndTimeMillis) {
        stopTimer(gameId);

        if (phaseEndTimeMillis <= 0) {
            log.warn("타이머 시작 실패: 종료 시간이 유효하지 않음. gameId={}, endTime={}", gameId, phaseEndTimeMillis);
            return;
        }

        Instant executionTime = Instant.ofEpochMilli(phaseEndTimeMillis);

        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                log.info("타이머 실행: gameId={}", gameId);
                gameService.advancePhase(gameId);
            } catch (Exception e) {
                log.error("타이머 실행 중 오류: gameId={}", gameId, e);
            } finally {
                scheduledTasks.remove(gameId);
            }
        }, executionTime);

        scheduledTasks.put(gameId, future);
        log.info("타이머 예약됨: gameId={}, endTime={}", gameId, executionTime);
    }

    /**
     * [Deprecated] 기존 호환용 - Redis에서 phaseEndTime 조회.
     *
     * @param gameId 게임 ID
     */
    @Deprecated
    public void startTimer(String gameId) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null || gameState.getPhaseEndTime() == null) {
            log.warn("타이머 시작 실패: 게임이 없거나 종료 시간이 설정되지 않음. gameId={}", gameId);
            return;
        }
        startTimer(gameId, gameState.getPhaseEndTime());
    }

    /**
     * 커스텀 작업과 실행 시간을 지정하여 타이머를 시작한다.
     *
     * @param gameId        게임 ID
     * @param task          실행할 작업
     * @param executionTime 실행 시각
     */
    public void startTimer(String gameId, Runnable task, Instant executionTime) {
        stopTimer(gameId);

        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("타이머 작업 실행 중 오류 발생: gameId={}", gameId, e);
            } finally {
                scheduledTasks.remove(gameId);
            }
        }, executionTime);

        scheduledTasks.put(gameId, future);
        log.info("타이머 설정됨: gameId={}, time={}", gameId, executionTime);
    }

    /**
     * 지정된 게임의 타이머를 취소한다.
     *
     * @param gameId 게임 ID
     */
    public void stopTimer(String gameId) {
        ScheduledFuture<?> future = scheduledTasks.remove(gameId);
        if (future != null) {
            future.cancel(false);
            log.info("타이머 취소됨: gameId={}", gameId);
        }
    }
}
