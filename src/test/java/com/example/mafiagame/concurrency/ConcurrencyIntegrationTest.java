package com.example.mafiagame.concurrency;

import com.example.mafiagame.global.concurrency.LockType;
import com.example.mafiagame.global.concurrency.service.UserStatsService;
import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.UserRole;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 제어 전략별 비교 테스트
 * 
 * 테스트 시나리오:
 * - 100개의 스레드가 동시에 같은 유저의 playCount를 1씩 증가
 * - 최종 playCount가 100이면 동시성 제어 성공
 * - 100 미만이면 Lost Update 발생 (동시성 제어 실패)
 * 
 * 포트폴리오 포인트:
 * - 각 락 방식별 정합성 보장 여부 증명
 * - 실패 케이스(NONE, SYNCHRONIZED - 다중 인스턴스)에서 Lost Update 시각화
 */
@SpringBootTest
class ConcurrencyIntegrationTest {

    @Autowired
    private UserStatsService userStatsService;

    @Autowired
    private UsersRepository usersRepository;

    private Long testUserId;
    private static final int THREAD_COUNT = 100;

    @BeforeEach
    void setUp() {
        // 테스트용 유저 생성 (playCount = 0으로 시작)
        Users testUser = Users.builder()
                .userLoginId("test_concurrency_" + System.currentTimeMillis())
                .nickname("테스트유저")
                .userRole(UserRole.USER)
                .provider(AuthProvider.LOCAL)
                .winCount(0)
                .playCount(0)
                .winRate(0.0)
                .build();

        Users savedUser = usersRepository.save(testUser);
        testUserId = savedUser.getUserId();
    }

    @Test
    @DisplayName("[NONE] 락 없음 - Lost Update 발생 예상")
    void testNoLock_shouldHaveLostUpdates() throws Exception {
        // given
        int initialPlayCount = getPlayCount();

        // when
        executeConcurrentIncrements(LockType.NONE);

        // then
        int finalPlayCount = getPlayCount();
        int actualIncrements = finalPlayCount - initialPlayCount;

        System.out.println("=".repeat(50));
        System.out.println("[NONE] 결과:");
        System.out.println("  - 예상 증가량: " + THREAD_COUNT);
        System.out.println("  - 실제 증가량: " + actualIncrements);
        System.out.println("  - Lost Updates: " + (THREAD_COUNT - actualIncrements));
        System.out.println("=".repeat(50));

        // 락 없이는 Lost Update가 발생하여 100보다 작을 가능성이 높음
        // 하지만 운 좋게 100이 될 수도 있으므로 assertThat만으로는 불안정
        // 따라서 결과 출력으로 시각화
    }

    @Test
    @DisplayName("[SYNCHRONIZED] Java synchronized - 단일 JVM에서는 정합성 보장")
    void testSynchronizedLock_shouldMaintainConsistency() throws Exception {
        // given
        int initialPlayCount = getPlayCount();

        // when
        executeConcurrentIncrements(LockType.SYNCHRONIZED);

        // then
        int finalPlayCount = getPlayCount();
        int actualIncrements = finalPlayCount - initialPlayCount;

        System.out.println("=".repeat(50));
        System.out.println("[SYNCHRONIZED] 결과:");
        System.out.println("  - 예상 증가량: " + THREAD_COUNT);
        System.out.println("  - 실제 증가량: " + actualIncrements);
        System.out.println("  - 정합성 보장 여부: " + (actualIncrements == THREAD_COUNT ? "✅ 성공" : "❌ 실패"));
        System.out.println("=".repeat(50));

        assertThat(actualIncrements).isEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("[PESSIMISTIC] DB Pessimistic Lock - 정합성 보장")
    void testPessimisticLock_shouldMaintainConsistency() throws Exception {
        // given
        int initialPlayCount = getPlayCount();

        // when
        executeConcurrentIncrements(LockType.PESSIMISTIC);

        // then
        int finalPlayCount = getPlayCount();
        int actualIncrements = finalPlayCount - initialPlayCount;

        System.out.println("=".repeat(50));
        System.out.println("[PESSIMISTIC] 결과:");
        System.out.println("  - 예상 증가량: " + THREAD_COUNT);
        System.out.println("  - 실제 증가량: " + actualIncrements);
        System.out.println("  - 정합성 보장 여부: " + (actualIncrements == THREAD_COUNT ? "✅ 성공" : "❌ 실패"));
        System.out.println("=".repeat(50));

        assertThat(actualIncrements).isEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("[OPTIMISTIC] DB Optimistic Lock - 재시도로 정합성 보장")
    void testOptimisticLock_shouldMaintainConsistencyWithRetry() throws Exception {
        // given
        int initialPlayCount = getPlayCount();

        // when
        AtomicInteger retryCount = new AtomicInteger(0);
        executeConcurrentIncrementsWithRetryCount(LockType.OPTIMISTIC, retryCount);

        // then
        int finalPlayCount = getPlayCount();
        int actualIncrements = finalPlayCount - initialPlayCount;

        System.out.println("=".repeat(50));
        System.out.println("[OPTIMISTIC] 결과:");
        System.out.println("  - 예상 증가량: " + THREAD_COUNT);
        System.out.println("  - 실제 증가량: " + actualIncrements);
        System.out.println("  - 정합성 보장 여부: " + (actualIncrements == THREAD_COUNT ? "✅ 성공" : "❌ 실패"));
        System.out.println("=".repeat(50));

        assertThat(actualIncrements).isEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("[REDISSON_SPIN] Redis Spin Lock - 분산 환경 정합성 보장")
    void testRedissonSpinLock_shouldMaintainConsistency() throws Exception {
        // given
        int initialPlayCount = getPlayCount();

        // when
        executeConcurrentIncrements(LockType.REDISSON_SPIN);

        // then
        int finalPlayCount = getPlayCount();
        int actualIncrements = finalPlayCount - initialPlayCount;

        System.out.println("=".repeat(50));
        System.out.println("[REDISSON_SPIN] 결과:");
        System.out.println("  - 예상 증가량: " + THREAD_COUNT);
        System.out.println("  - 실제 증가량: " + actualIncrements);
        System.out.println("  - 정합성 보장 여부: " + (actualIncrements == THREAD_COUNT ? "✅ 성공" : "❌ 실패"));
        System.out.println("=".repeat(50));

        assertThat(actualIncrements).isEqualTo(THREAD_COUNT);
    }

    @Test
    @DisplayName("[REDISSON_PUBSUB] Redis Pub-Sub Lock - 분산 환경 정합성 보장 (효율적)")
    void testRedissonPubSubLock_shouldMaintainConsistency() throws Exception {
        // given
        int initialPlayCount = getPlayCount();

        // when
        executeConcurrentIncrements(LockType.REDISSON_PUBSUB);

        // then
        int finalPlayCount = getPlayCount();
        int actualIncrements = finalPlayCount - initialPlayCount;

        System.out.println("=".repeat(50));
        System.out.println("[REDISSON_PUBSUB] 결과:");
        System.out.println("  - 예상 증가량: " + THREAD_COUNT);
        System.out.println("  - 실제 증가량: " + actualIncrements);
        System.out.println("  - 정합성 보장 여부: " + (actualIncrements == THREAD_COUNT ? "✅ 성공" : "❌ 실패"));
        System.out.println("=".repeat(50));

        assertThat(actualIncrements).isEqualTo(THREAD_COUNT);
    }

    /**
     * 동시에 THREAD_COUNT개의 스레드로 increment 실행
     */
    private void executeConcurrentIncrements(LockType lockType) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1); // 동시 시작을 위한 래치

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 스레드가 준비될 때까지 대기
                    userStatsService.incrementPlayCount(testUserId, lockType);
                } catch (Exception e) {
                    System.err.println("Error in thread: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        startLatch.countDown(); // 모든 스레드 동시에 시작!
        latch.await(); // 모든 스레드 완료 대기
        executor.shutdown();
    }

    /**
     * 재시도 횟수 카운트 포함 버전
     */
    private void executeConcurrentIncrementsWithRetryCount(LockType lockType, AtomicInteger retryCount)
            throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    userStatsService.incrementPlayCount(testUserId, lockType);
                } catch (Exception e) {
                    retryCount.incrementAndGet();
                    System.err.println("Retry or Error: " + e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        startLatch.countDown();
        latch.await();
        executor.shutdown();
    }

    private int getPlayCount() {
        return usersRepository.findById(testUserId)
                .map(Users::getPlayCount)
                .orElse(0);
    }
}
