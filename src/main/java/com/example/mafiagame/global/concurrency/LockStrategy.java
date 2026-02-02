package com.example.mafiagame.global.concurrency;

import java.util.function.Supplier;

/**
 * 동시성 제어 전략 인터페이스
 * Strategy Pattern 적용으로 런타임에 락 방식 교체 가능
 * 
 * 사용법:
 * - API 파라미터로 lockType을 받아 동적으로 전략 선택
 * - nGrinder/k6 부하 테스트 시 파라미터만 변경하여 A/B 테스트 가능
 */
public interface LockStrategy {

    /**
     * 락을 획득하고 비즈니스 로직을 실행
     * 
     * @param lockKey 락을 식별하는 키 (예: "user:123")
     * @param action  락 보호 하에 실행할 로직
     * @return 로직 실행 결과
     */
    <T> T executeWithLock(String lockKey, Supplier<T> action);

    /**
     * 반환값 없는 로직용 오버로드
     */
    default void executeWithLock(String lockKey, Runnable action) {
        executeWithLock(lockKey, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 전략 이름 반환 (로깅/메트릭용)
     */
    String getStrategyName();
}
