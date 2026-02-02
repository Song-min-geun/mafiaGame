package com.example.mafiagame.global.concurrency.strategy;

import com.example.mafiagame.global.concurrency.LockStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis Pub-Sub Lock 전략 (Redisson RLock)
 * 
 * 동작 방식:
 * - Redisson의 RLock은 내부적으로 Pub-Sub 채널 구독
 * - 락 해제 시 Redis Pub-Sub으로 대기 중인 클라이언트에 알림
 * - Spin Lock과 달리 반복 polling 없이 효율적으로 대기
 * 
 * 장점: 분산 환경 지원, Pub-Sub으로 효율적 대기, CPU 부하 적음
 * 단점: Redis 외부 의존성 (SPOF), 구현 복잡도 증가
 * 
 * 트랜잭션-락 순서 (매우 중요!):
 * ┌───────────────────────────────────────────────────┐
 * │ Lock 획득 → TX 시작 → 비즈니스 로직 → TX 커밋 → Lock 해제 │
 * └───────────────────────────────────────────────────┘
 * 
 * 주의: TX 커밋 전에 Lock을 해제하면 다른 스레드가
 * 아직 커밋되지 않은 데이터를 읽을 수 있음 (정합성 깨짐)
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedissonPubSubLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;
    private static final String LOCK_PREFIX = "lock:pubsub:";

    // 락 설정
    private static final long WAIT_TIME_SEC = 5; // 락 대기 최대 시간 (초)
    // -1 enables Redisson watchdog (auto-extends every 30s/3 = 10s while thread
    // holds lock)
    private static final long LEASE_TIME_SEC = -1;

    @Override
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + lockKey);

        try {
            // Pub-Sub 기반 대기: tryLock 내부적으로 Redis Pub-Sub 채널 구독
            if (!lock.tryLock(WAIT_TIME_SEC, LEASE_TIME_SEC, TimeUnit.SECONDS)) {
                throw new RuntimeException("Pub-Sub Lock 획득 실패 (타임아웃): " + lockKey);
            }

            // ⚠️ 핵심: 여기서 action.get() 호출
            // action 내부에서 @Transactional 메서드 호출 시
            // TX 커밋이 완료된 후 finally에서 unlock 수행됨
            return action.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Pub-Sub Lock 획득 중 인터럽트", e);
        } finally {
            // ⚠️ 주의: TX 커밋이 완료된 후에만 unlock!!!
            // action.get() 내부의 @Transactional 메서드가 완료되어야 여기에 도달
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("Pub-Sub Lock 해제: {}", lockKey);
            }
        }
    }

    @Override
    public String getStrategyName() {
        return "REDISSON_PUBSUB";
    }
}
