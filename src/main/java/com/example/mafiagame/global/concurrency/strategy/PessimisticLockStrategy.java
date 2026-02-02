package com.example.mafiagame.global.concurrency.strategy;

import com.example.mafiagame.global.concurrency.LockStrategy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * DB Pessimistic Lock (비관적 락) 전략
 * 
 * 동작 방식:
 * - Repository에서 @Lock(PESSIMISTIC_WRITE)로 SELECT ... FOR UPDATE 실행
 * - 해당 행에 X-Lock(Exclusive Lock) 획득
 * - 다른 트랜잭션은 락 해제까지 대기
 * 
 * 장점: 강력한 정합성 보장
 * 단점: 락 대기 시간 증가, Deadlock 위험
 * 
 * InnoDB 분석 포인트:
 * - PK 동등 조건(=) 검색 시 Record Lock만 발생 (Gap Lock 없음)
 * - 범위 조건 검색 시 Gap Lock 발생 가능
 * 
 * 주의: 실제 락은 Repository 쿼리에서 처리되며,
 * 이 전략은 트랜잭션 경계 관리 역할
 */
@Component
public class PessimisticLockStrategy implements LockStrategy {

    @Override
    @Transactional
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        // Pessimistic Lock은 Repository 쿼리에서 FOR UPDATE로 처리
        // 여기서는 트랜잭션 경계만 관리
        return action.get();
    }

    @Override
    public String getStrategyName() {
        return "PESSIMISTIC";
    }
}
