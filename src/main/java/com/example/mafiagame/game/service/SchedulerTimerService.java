package com.example.mafiagame.game.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.repository.GameStateRepository;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

@Slf4j
@Service
public class SchedulerTimerService {

    private final TaskScheduler taskScheduler;
    private final GameService gameService;
    private final GameStateRepository gameStateRepository;

    // 게임별 예약된 작업을 관리하는 맵 (GameId -> ScheduledFuture)
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    public SchedulerTimerService(TaskScheduler taskScheduler, @Lazy GameService gameService,
            GameStateRepository gameStateRepository) {
        this.taskScheduler = taskScheduler;
        this.gameService = gameService;
        this.gameStateRepository = gameStateRepository;
    }

    public void startTimer(String gameId) {
        stopTimer(gameId);

        // 2. 게임 정보 가져오기 (Redis에서 최신 상태 조회)
        // GameState는 실시간 상태 정보를 담고 있음
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);

        if (gameState == null || gameState.getPhaseEndTime() == null) {
            log.warn("타이머 시작 실패: 게임이 없거나 종료 시간이 설정되지 않음. gameId={}", gameId);
            return;
        }

        // 3. 실행 시간 계산
        Instant executionTime = Instant.ofEpochMilli(gameState.getPhaseEndTime());

        // 4. 스케줄링
        ScheduledFuture<?> future = taskScheduler.schedule(() -> {
            try {
                log.info("타이머 실행: gameId={}, phase={}", gameId, gameState.getGamePhase());
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
