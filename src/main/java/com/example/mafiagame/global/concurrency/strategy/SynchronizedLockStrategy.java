package com.example.mafiagame.global.concurrency.strategy;

import com.example.mafiagame.global.concurrency.LockStrategy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Java Synchronized 기반 락 전략
 * 
 * 장점: 구현 단순, 외부 의존성 없음
 * 단점: 단일 JVM에서만 동작 → 스케일아웃 시 무용지물
 * 
 * 포트폴리오 포인트:
 * - 분산 환경 테스트 시 Lost Update 발생을 증명할 수 있음
 * - "왜 분산 락이 필요한가?"에 대한 근거
 */
@Component
public class SynchronizedLockStrategy implements LockStrategy {

    // lockKey별로 별도의 락 객체를 관리 (같은 키에 대해서만 동기화)
    private final Map<String, Object> lockMap = new ConcurrentHashMap<>();

    @Override
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        Object lock = lockMap.computeIfAbsent(lockKey, k -> new Object());

        synchronized (lock) {
            return action.get();
        }
    }

    @Override
    public String getStrategyName() {
        return "SYNCHRONIZED";
    }
}
