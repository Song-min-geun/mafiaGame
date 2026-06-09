package com.example.mafiagame.global.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Core Redis 서킷 브레이커 빈 등록.
 * 실제 설정값은 application.properties의
 * resilience4j.circuitbreaker.instances.coreRedis.* 에서 관리된다.
 */
@Configuration
public class CoreRedisCircuitBreakerConfig {

    /**
     * application.properties에 정의된 'coreRedis' 인스턴스 설정으로 CircuitBreaker 생성.
     * <ul>
     *   <li>실패율 50% 이상 → OPEN</li>
     *   <li>슬로우 콜 500ms 이상 & 80% 이상 → OPEN</li>
     *   <li>슬라이딩 윈도우 20개, 최소 10개 호출 후 판단</li>
     *   <li>OPEN 상태 30초 대기 → HALF_OPEN → 5회 시도</li>
     * </ul>
     */
    @Bean
    public CircuitBreaker coreRedisCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("coreRedis");
    }
}
