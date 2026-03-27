package com.example.mafiagame.game.service;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.repository.GameTimerRepository;
import com.example.mafiagame.game.timer.GameTimerJob;
import com.example.mafiagame.game.timer.GameTimerMeta;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class GameTimerWorker {

    private final GameTimerRepository gameTimerRepository;
    private final GameStateRepository gameStateRepository;
    private final GameService gameService;

    @Value("${game.timer.worker.batch-size:20}")
    private int batchSize;

    @Value("${game.timer.worker.processing-lease-ms:15000}")
    private long processingLeaseMillis;

    @Scheduled(fixedDelayString = "${game.timer.worker.poll-delay-ms:500}")
    public void pollDueTimers() {
        long now = System.currentTimeMillis();
        List<GameTimerJob> dueTimers = gameTimerRepository.claimDueTimers(now, batchSize, processingLeaseMillis);
        for (GameTimerJob timerJob : dueTimers) {
            process(timerJob);
        }
    }

    @Scheduled(fixedDelayString = "${game.timer.worker.requeue-delay-ms:2000}")
    public void requeueExpiredProcessingTimers() {
        long now = System.currentTimeMillis();
        List<GameTimerJob> expiredTimers = gameTimerRepository.claimExpiredProcessing(now, batchSize);
        for (GameTimerJob timerJob : expiredTimers) {
            if (!isProcessable(timerJob)) {
                continue;
            }

            boolean requeued = gameTimerRepository.requeueIfCurrent(timerJob, now);
            if (requeued) {
                log.warn("[GameTimerWorker] processing lease expired. timer requeued: gameId={}, phase={}, currentPhase={}",
                        timerJob.gameId(), timerJob.phase(), timerJob.currentPhase());
            }
        }
    }

    private void process(GameTimerJob timerJob) {
        boolean ackRequired = false;
        try {
            if (!isProcessable(timerJob)) {
                log.debug("[GameTimerWorker] stale timer skipped: gameId={}, phase={}, currentPhase={}",
                        timerJob.gameId(), timerJob.phase(), timerJob.currentPhase());
                ackRequired = true;
                return;
            }

            log.info("[GameTimerWorker] due timer claimed: gameId={}, phase={}, currentPhase={}",
                    timerJob.gameId(), timerJob.phase(), timerJob.currentPhase());
            ackRequired = gameService.advancePhase(timerJob.gameId());
        } catch (Exception e) {
            log.error("[GameTimerWorker] timer processing failed: gameId={}", timerJob.gameId(), e);
        } finally {
            if (ackRequired) {
                gameTimerRepository.ack(timerJob);
            }
        }
    }

    private boolean isProcessable(GameTimerJob timerJob) {
        if (!gameTimerRepository.isCurrentTimer(timerJob)) {
            return false;
        }

        GameTimerMeta meta = gameStateRepository.findMeta(timerJob.gameId()).orElse(null);
        if (meta == null) {
            return false;
        }

        if (meta.status() != GameStatus.IN_PROGRESS) {
            return false;
        }

        if (meta.phase() != timerJob.phase()) {
            return false;
        }

        if (meta.currentPhase() != timerJob.currentPhase()) {
            return false;
        }

        return timerJob.timerToken().equals(meta.timerToken());
    }
}
