package com.example.mafiagame.global.concurrency.service;

import com.example.mafiagame.global.concurrency.LockType;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 트랜잭션 분리를 위한 내부 서비스
 * 
 * 목적:
 * - Redisson Lock 내부에서 호출
 * - 트랜잭션 커밋이 Lock 해제 전에 완료됨을 보장
 * 
 * 트랜잭션-락 순서:
 * ┌─────────────────────────────────────────────────────────────┐
 * │ Lock 획득 → @Transactional 메서드 호출 → TX 커밋 → Lock 해제 │
 * └─────────────────────────────────────────────────────────────┘
 * 
 * Spring AOP 프록시 특성상, 같은 클래스 내부 호출은 트랜잭션이 적용되지 않음
 * 따라서 별도 서비스로 분리하여 프록시를 통한 호출이 되도록 함
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InternalUserStatsService {

        private final UsersRepository usersRepository;

        /**
         * 유저 playCount 증가 (트랜잭션 보장)
         * 
         * @param userId   유저 ID
         * @param lockType 사용 중인 락 타입 (Pessimistic일 경우 FOR UPDATE 사용)
         */
        @Transactional
        public void doIncrement(Long userId, LockType lockType) {
                Users user = switch (lockType) {
                        case PESSIMISTIC -> usersRepository.findByIdWithPessimisticLock(userId)
                                        .orElseThrow(() -> new RuntimeException("User not found: " + userId));
                        default -> usersRepository.findById(userId)
                                        .orElseThrow(() -> new RuntimeException("User not found: " + userId));
                };

                user.incrementPlayCount();
                usersRepository.save(user);

                if (user.getPlayCount() % 100000 == 0)
                        log.warn("[전적 업데이트] userId={}, lockType={}, newPlayCount={}",
                                        userId, lockType, user.getPlayCount());
        }

        /**
         * 유저 winCount + playCount 증가 (트랜잭션 보장)
         */
        @Transactional
        public void doIncrementWithWin(Long userId, LockType lockType) {
                Users user = switch (lockType) {
                        case PESSIMISTIC -> usersRepository.findByIdWithPessimisticLock(userId)
                                        .orElseThrow(() -> new RuntimeException("User not found: " + userId));
                        default -> usersRepository.findById(userId)
                                        .orElseThrow(() -> new RuntimeException("User not found: " + userId));
                };

                user.incrementPlayCount();
                user.incrementWinCount();
                user.updateWinRate();
                usersRepository.save(user);

                log.debug("[전적 업데이트 with 승리] userId={}, lockType={}, winRate={}",
                                userId, lockType, user.getWinRate());
        }
}
