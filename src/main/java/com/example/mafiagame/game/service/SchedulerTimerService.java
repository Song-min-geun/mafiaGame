package com.example.mafiagame.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class SchedulerTimerService {

    private final TaskScheduler taskScheduler;
    private final GameService gameService;

    // 게임별 예약된 작업을 관리하는 맵 (GameId -> TimerRegistration)
    private final Map<String, TimerRegistration> scheduledTasks = new ConcurrentHashMap<>();
    private final AtomicLong timerSequence = new AtomicLong();

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
        TimerRegistration registration = new TimerRegistration(timerSequence.incrementAndGet(), phaseEndTimeMillis);

        boolean scheduled = scheduleTimer(gameId, registration, () -> {
            log.info("타이머 실행: gameId={}, timerId={}", gameId, registration.id());
            gameService.advancePhaseIfTimerCurrent(gameId, registration.phaseEndTimeMillis());
        }, executionTime);

        if (scheduled) {
            log.info("타이머 예약됨: gameId={}, timerId={}, endTime={}", gameId, registration.id(), executionTime);
        }
    }

    public void startTimer(String gameId, Runnable task, Instant executionTime) {
        stopTimer(gameId);
        TimerRegistration registration = new TimerRegistration(timerSequence.incrementAndGet(), null);

        boolean scheduled = scheduleTimer(gameId, registration, task, executionTime);

        if (scheduled) {
            log.info("타이머 설정됨: gameId={}, timerId={}, time={}", gameId, registration.id(), executionTime);
        }
    }

    public void stopTimer(String gameId) {
        TimerRegistration registration = scheduledTasks.remove(gameId);
        if (registration != null) {
            registration.cancel();
            log.info("타이머 취소됨: gameId={}, timerId={}", gameId, registration.id());
        }
    }

    private boolean scheduleTimer(String gameId, TimerRegistration registration, Runnable task, Instant executionTime) {
        scheduledTasks.put(gameId, registration);

        ScheduledFuture<?> future;
        try {
            future = taskScheduler.schedule(() -> runIfCurrent(gameId, registration, task), executionTime);
        } catch (RuntimeException e) {
            scheduledTasks.remove(gameId, registration);
            throw e;
        }

        if (future == null) {
            scheduledTasks.remove(gameId, registration);
            log.warn("타이머 예약 실패: scheduler가 future를 반환하지 않음. gameId={}, timerId={}", gameId, registration.id());
            return false;
        }

        registration.setFuture(future);
        if (scheduledTasks.get(gameId) != registration) {
            future.cancel(false);
            return false;
        }
        return true;
    }

    private void runIfCurrent(String gameId, TimerRegistration registration, Runnable task) {
        if (!scheduledTasks.remove(gameId, registration)) {
            log.info("만료되었거나 교체된 타이머 실행 무시: gameId={}, timerId={}", gameId, registration.id());
            return;
        }

        try {
            task.run();
        } catch (Exception e) {
            log.error("타이머 작업 실행 중 오류 발생: gameId={}, timerId={}", gameId, registration.id(), e);
        }
    }

    private static final class TimerRegistration {
        private final long id;
        private final Long phaseEndTimeMillis;
        private volatile ScheduledFuture<?> future;

        private TimerRegistration(long id, Long phaseEndTimeMillis) {
            this.id = id;
            this.phaseEndTimeMillis = phaseEndTimeMillis;
        }

        private long id() {
            return id;
        }

        private long phaseEndTimeMillis() {
            return phaseEndTimeMillis;
        }

        private void setFuture(ScheduledFuture<?> future) {
            this.future = future;
        }

        private void cancel() {
            ScheduledFuture<?> currentFuture = future;
            if (currentFuture != null) {
                currentFuture.cancel(false);
            }
        }
    }
}
