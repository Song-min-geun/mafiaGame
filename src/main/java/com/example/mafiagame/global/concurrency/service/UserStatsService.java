package com.example.mafiagame.global.concurrency.service;

import com.example.mafiagame.global.concurrency.LockStrategy;
import com.example.mafiagame.global.concurrency.LockStrategyFactory;
import com.example.mafiagame.global.concurrency.LockType;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 유저 전적 업데이트 서비스 (동시성 제어 A/B 테스트용)
 * 
 * 특징:
 * - Strategy Pattern으로 런타임에 락 방식 교체 가능
 * - API 파라미터로 lockType을 받아 동적으로 전략 선택
 * - nGrinder/k6 부하 테스트 시 파라미터만 변경하여 비교 테스트 가능
 * 
 * Optimistic Lock 주의사항:
 * - ObjectOptimisticLockingFailureException은 TX 커밋 시점에 발생
 * - 따라서 @Retryable은 Service 메서드 레벨에 적용해야 함
 * - Strategy 내부 try-catch로는 잡히지 않음
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserStatsService {

    private final UsersRepository usersRepository;
    private final LockStrategyFactory lockStrategyFactory;
    private final InternalUserStatsService internalService;

    /**
     * 유저 playCount 증가 (동시성 제어 적용)
     * 
     * @param userId   유저 ID
     * @param lockType 사용할 락 타입
     */
    @Retryable(retryFor = ObjectOptimisticLockingFailureException.class, maxAttempts = 3, backoff = @Backoff(delay = 100))
    public void incrementPlayCount(Long userId, LockType lockType) {
        LockStrategy strategy = lockStrategyFactory.getStrategy(lockType);

        log.debug("[동시성 테스트] 시작 - userId={}, lockType={}", userId, lockType);

        strategy.executeWithLock("user:" + userId, () -> {
            // 내부 트랜잭션 서비스 호출 (트랜잭션 커밋 시점 보장)
            internalService.doIncrement(userId, lockType);
        });

        log.debug("[동시성 테스트] 완료 - userId={}, lockType={}", userId, lockType);
    }

    /**
     * 유저 통계 조회 (읽기 전용)
     */
    @Transactional(readOnly = true)
    public Users getStats(Long userId) {
        return usersRepository.findById(userId).orElse(null);
    }

    /**
     * 유저 playCount 조회
     */
    @Transactional(readOnly = true)
    public Integer getPlayCount(Long userId) {
        return usersRepository.findById(userId)
                .map(Users::getPlayCount)
                .orElse(null);
    }
}
