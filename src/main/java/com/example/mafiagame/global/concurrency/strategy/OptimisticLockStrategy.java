package com.example.mafiagame.global.concurrency.strategy;

import com.example.mafiagame.global.concurrency.LockStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * DB Optimistic Lock (낙관적 락) 전략
 * 
 * 동작 방식:
 * - Entity에 @Version 필드 추가
 * - UPDATE 시 WHERE version = X 조건 추가
 * - 버전 불일치 시 ObjectOptimisticLockingFailureException 발생
 * 
 * 장점: 충돌이 적을 때 최고 성능 (락 대기 없음)
 * 단점: 충돌 빈발 시 재시도 오버헤드
 * 
 * 주의:
 * - @Retryable은 Service 레벨(UserStatsService)에 적용해야 함
 * - 예외는 트랜잭션 커밋 시점(메서드 종료)에 발생
 * - Strategy 내부 try-catch로는 잡히지 않음
 */
@Component
public class OptimisticLockStrategy implements LockStrategy {

    @Override
    @Transactional
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        // Optimistic Lock은 Entity의 @Version으로 처리
        // 여기서는 트랜잭션 경계만 관리
        // 재시도 로직은 호출하는 Service에서 @Retryable로 처리
        return action.get();
    }

    @Override
    public String getStrategyName() {
        return "OPTIMISTIC";
    }
}
