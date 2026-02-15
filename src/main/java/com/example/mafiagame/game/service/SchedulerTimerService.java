package com.example.mafiagame.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class SchedulerTimerService {

    private final TaskScheduler taskScheduler;
    private final GameService gameService;

    // 게임별 예약된 작업을 관리하는 맵 (GameId -> ScheduledFuture)
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public SchedulerTimerService(TaskScheduler taskScheduler, @Lazy GameService gameService) {
        this.taskScheduler = taskScheduler;
        this.gameService = gameService;
    }

    /**
     * 타이머 시작 (phaseEndTime 직접 전달 - Race Condition 방지)
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

    public void stopTimer(String gameId) {
        ScheduledFuture<?> future = scheduledTasks.remove(gameId);
        if (future != null) {
            future.cancel(false);
            log.info("타이머 취소됨: gameId={}", gameId);
        }
    }
}
