package com.example.mafiagame.settlement.job;

import org.springframework.batch.core.Step;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.example.mafiagame.inventory.service.InventoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 만료 아이템 정리 Spring Batch Job.
 *
 * <p>매일 실행하여 유효기간이 지난 시즌패스, 부스트 등을 비활성화한다.</p>
 */
@Configuration
@Slf4j
@RequiredArgsConstructor
public class ExpiredItemCleanupJobConfig {

    private final InventoryService inventoryService;

    /**
     * 만료 아이템 정리 Job.
     */
    @Bean
    public Job expiredItemCleanupJob(JobRepository jobRepository, Step cleanupStep) {
        return new JobBuilder("expiredItemCleanupJob", jobRepository)
                .start(cleanupStep)
                .build();
    }

    /**
     * 정리 Step.
     */
    @Bean
    public Step cleanupStep(JobRepository jobRepository, PlatformTransactionManager txManager) {
        return new StepBuilder("cleanupStep", jobRepository)
                .tasklet(cleanupTasklet(), txManager)
                .build();
    }

    @Bean
    public Tasklet cleanupTasklet() {
        return (contribution, chunkContext) -> {
            int count = inventoryService.deactivateExpiredItems();
            log.info("[만료 아이템 정리 Batch] 비활성화 건수={}", count);
            return RepeatStatus.FINISHED;
        };
    }
}
