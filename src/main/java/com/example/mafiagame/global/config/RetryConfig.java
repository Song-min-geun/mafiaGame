package com.example.mafiagame.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Spring Retry 활성화 설정
 * 
 * Optimistic Lock 재시도를 위해 필요:
 * - @Retryable 어노테이션 활성화
 * - ObjectOptimisticLockingFailureException 발생 시 자동 재시도
 */
@Configuration
@EnableRetry
public class RetryConfig {
}
