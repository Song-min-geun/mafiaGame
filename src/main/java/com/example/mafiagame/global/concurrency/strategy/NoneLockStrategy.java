package com.example.mafiagame.global.concurrency.strategy;

import com.example.mafiagame.global.concurrency.LockStrategy;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 락 없음 전략 (대조군)
 * 동시성 제어 없이 그대로 실행 - Lost Update 발생 가능
 * 
 * 성능 테스트 시 baseline으로 사용
 */
@Component
public class NoneLockStrategy implements LockStrategy {

    @Override
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        // 락 없이 바로 실행
        return action.get();
    }

    @Override
    public String getStrategyName() {
        return "NONE";
    }
}
