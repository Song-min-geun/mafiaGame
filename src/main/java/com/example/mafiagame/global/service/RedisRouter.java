package com.example.mafiagame.global.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.function.Supplier;

/**
 * Core/Support Redis 라우팅 + 서킷 브레이커 통합 서비스.
 *
 * <p><b>정상 상태 (CLOSED)</b>: Core Redis(6379)로 게임 로직 라우팅.</p>
 * <p><b>서킷 오픈 (OPEN)</b>: Support Redis(6380)가 Core의 메인 비즈니스 로직 분담.
 * Support Redis의 read-through 캐시(Top10, 칭호)는 DB로 직접 폴백.</p>
 *
 * <p>모든 Core Redis 호출은 {@link #executeOnCore(Supplier)} 또는
 * {@link #runOnCore(Runnable)}로 감싸 서킷 브레이커가 실패를 추적한다.</p>
 */
@Service
@Slf4j
public class RedisRouter {

    private final StringRedisTemplate coreStringTemplate;
    private final StringRedisTemplate supportStringTemplate;
    private final RedisTemplate<String, Object> coreObjectTemplate;
    private final RedisTemplate<String, Object> supportObjectTemplate;
    private final RedissonClient coreRedisson;
    private final RedissonClient supportRedisson;
    private final CircuitBreaker circuitBreaker;

    public RedisRouter(
            @Qualifier("coreStringRedisTemplate") StringRedisTemplate coreStringTemplate,
            @Qualifier("stringRedisTemplate") StringRedisTemplate supportStringTemplate,
            @Qualifier("coreRedisTemplate") RedisTemplate<String, Object> coreObjectTemplate,
            @Qualifier("redisTemplate") RedisTemplate<String, Object> supportObjectTemplate,
            @Qualifier("coreRedissonClient") RedissonClient coreRedisson,
            @Qualifier("redissonClient") RedissonClient supportRedisson,
            CircuitBreakerRegistry circuitBreakerRegistry) {
        this.coreStringTemplate = coreStringTemplate;
        this.supportStringTemplate = supportStringTemplate;
        this.coreObjectTemplate = coreObjectTemplate;
        this.supportObjectTemplate = supportObjectTemplate;
        this.coreRedisson = coreRedisson;
        this.supportRedisson = supportRedisson;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("coreRedis");

        // 서킷 상태 변경 시 로깅
        this.circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn(
                        "[RedisRouter] Circuit state changed: {} → {}",
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }

    // ── 활성 템플릿 ──────────────────────────────────────────────

    /**
     * 서킷 상태에 따라 활성 StringRedisTemplate 반환.
     * CLOSED → Core Redis, OPEN/FORCED_OPEN → Support Redis.
     */
    public StringRedisTemplate activeStringTemplate() {
        return isCircuitOpen() ? supportStringTemplate : coreStringTemplate;
    }

    /**
     * 서킷 상태에 따라 활성 RedisTemplate&lt;String, Object&gt; 반환.
     */
    public RedisTemplate<String, Object> activeObjectTemplate() {
        return isCircuitOpen() ? supportObjectTemplate : coreObjectTemplate;
    }

    /**
     * 서킷 상태에 따라 활성 RedissonClient 반환.
     */
    public RedissonClient activeRedisson() {
        return isCircuitOpen() ? supportRedisson : coreRedisson;
    }

    // ── 서킷 브레이커로 감싼 Core Redis 실행 ──────────────────────

    /**
     * 반환값이 있는 Core Redis 작업을 서킷 브레이커로 감싸 실행.
     * 서킷이 이미 OPEN이면 CallNotPermittedException이 발생하며,
     * 호출자는 이를 catch하여 Support Redis 또는 DB 폴백을 수행해야 한다.
     */
    public <T> T executeOnCore(Supplier<T> operation) {
        return circuitBreaker.executeSupplier(operation);
    }

    /**
     * 반환값이 없는 Core Redis 작업을 서킷 브레이커로 감싸 실행.
     */
    public void runOnCore(Runnable operation) {
        circuitBreaker.executeRunnable(operation);
    }

    // ── 상태 조회 ────────────────────────────────────────────────

    /**
     * 서킷이 OPEN 또는 FORCED_OPEN 상태인지 반환.
     * true이면 Support Redis가 Core 역할을 분담하고,
     * Support의 read-through 캐시는 DB를 직접 조회한다.
     */
    public boolean isCircuitOpen() {
        CircuitBreaker.State state = circuitBreaker.getState();
        return state == CircuitBreaker.State.OPEN
                || state == CircuitBreaker.State.FORCED_OPEN;
    }

    /**
     * Support Redis의 read-through 캐시(Top10, 칭호)를 우회해서 DB를 직접 조회해야 하는지 반환.
     * 서킷이 열려 Support Redis가 게임 로직을 처리하는 동안에는 캐시 대신 DB를 직접 사용한다.
     */
    public boolean shouldBypassSupportCache() {
        return isCircuitOpen();
    }

    /**
     * 현재 서킷 브레이커 상태 문자열 반환 (모니터링/로깅 용도).
     */
    public String getCircuitState() {
        return circuitBreaker.getState().name();
    }

    /**
     * Core Redis 전용 StringRedisTemplate 직접 접근 (CoreRedisPressureService 등 모니터링 용도).
     * 일반 비즈니스 로직에서는 activeStringTemplate()을 사용한다.
     */
    public StringRedisTemplate coreStringTemplate() {
        return coreStringTemplate;
    }
}
