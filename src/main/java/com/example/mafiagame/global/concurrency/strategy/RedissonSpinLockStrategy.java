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
 * Redis Spin Lock 전략
 * 
 * 동작 방식:
 * - 락 획득 실패 시 짧은 간격으로 반복 시도 (Polling)
 * - Redisson의 tryLock()을 반복 호출하는 방식
 * 
 * 장점: 구현 단순, 분산 환경 지원
 * 단점: Spin Polling으로 Redis CPU/Network 부하 증가
 * 
 * 포트폴리오 포인트:
 * - Spin Lock vs Pub-Sub Lock 성능 비교
 * - Redis CPU 사용량 그래프로 차이 시각화
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RedissonSpinLockStrategy implements LockStrategy {

    private final RedissonClient redissonClient;
    private static final String LOCK_PREFIX = "lock:spin:";

    // Spin 설정
    private static final int MAX_RETRY = 50; // 최대 재시도 횟수
    private static final long RETRY_DELAY_MS = 100; // 재시도 간격 (ms)
    private static final long LEASE_TIME_SEC = 10; // 락 자동 해제 시간 (초)

    @Override
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + lockKey);
        boolean acquired = false;

        try {
            // Spin: 락 획득까지 반복 시도
            for (int i = 0; i < MAX_RETRY; i++) {
                acquired = lock.tryLock(0, LEASE_TIME_SEC, TimeUnit.SECONDS);
                if (acquired) {
                    break;
                }
                Thread.sleep(RETRY_DELAY_MS);
            }

            if (!acquired) {
                throw new RuntimeException("Spin Lock 획득 실패 (최대 재시도 초과): " + lockKey);
            }

            return action.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Spin Lock 획득 중 인터럽트", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public String getStrategyName() {
        return "REDISSON_SPIN";
    }
}
