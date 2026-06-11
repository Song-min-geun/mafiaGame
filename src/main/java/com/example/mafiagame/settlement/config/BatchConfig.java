package com.example.mafiagame.settlement.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Batch 및 스케줄링 기본 설정.
 *
 * <p>{@code spring.batch.job.enabled=false}로 애플리케이션 시작 시 자동 실행을 방지하고,
 * 스케줄러(@Scheduled)를 통해 명시적으로 실행한다.</p>
 */
@Configuration
@EnableBatchProcessing
@EnableScheduling
public class BatchConfig {
    // Spring Batch 메타 테이블은 application.properties의
    // spring.batch.jdbc.initialize-schema=always 설정으로 자동 생성
}
