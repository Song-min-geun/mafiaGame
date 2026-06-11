package com.example.mafiagame.settlement.job;

import java.time.LocalDate;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Batch Job 스케줄러.
 *
 * <p>cron 표현식으로 정해진 시간에 자동 실행한다.</p>
 */
@Component
@Slf4j
public class BatchJobScheduler {

    private final JobLauncher jobLauncher;
    private final Job dailySettlementJob;
    private final Job expiredItemCleanupJob;

    public BatchJobScheduler(
            JobLauncher jobLauncher,
            @Qualifier("dailySettlementJob") Job dailySettlementJob,
            @Qualifier("expiredItemCleanupJob") Job expiredItemCleanupJob) {
        this.jobLauncher = jobLauncher;
        this.dailySettlementJob = dailySettlementJob;
        this.expiredItemCleanupJob = expiredItemCleanupJob;
    }

    /**
     * 매일 새벽 3시: 일일 정산 Job 실행.
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void runDailySettlementJob() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("targetDate", LocalDate.now().minusDays(1).toString())
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            jobLauncher.run(dailySettlementJob, params);
            log.info("[스케줄러] 일일 정산 Job 실행 완료");
        } catch (Exception e) {
            log.error("[스케줄러] 일일 정산 Job 실행 실패", e);
        }
    }

    /**
     * 매일 자정: 만료 아이템 정리 Job 실행.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void runExpiredItemCleanupJob() {
        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            jobLauncher.run(expiredItemCleanupJob, params);
            log.info("[스케줄러] 만료 아이템 정리 Job 실행 완료");
        } catch (Exception e) {
            log.error("[스케줄러] 만료 아이템 정리 Job 실행 실패", e);
        }
    }
}
