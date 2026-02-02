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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 제어 방식별 성능 비교 테스트
 * 
 * 측정 지표:
 * - 총 소요 시간 (ms)
 * - 평균 응답 시간 (ms)
 * - TPS (Transactions Per Second)
 * 
 * 포트폴리오 포인트:
 * - 각 락 방식별 성능 차이 수치화
 * - 정합성 vs 성능 트레이드오프 시각화
 */
@SpringBootTest
class ConcurrencyPerformanceTest {

    @Autowired
    private UserStatsService userStatsService;

    @Autowired
    private UsersRepository usersRepository;

    private Long testUserId;
    private static final int THREAD_COUNT = 50;
    private static final int ITERATIONS_PER_THREAD = 10;

    @BeforeEach
    void setUp() {
        Users testUser = Users.builder()
                .userLoginId("perf_test_" + System.currentTimeMillis())
                .nickname("성능테스트유저")
                .userRole(UserRole.USER)
                .provider(AuthProvider.LOCAL)
                .winCount(0)
                .playCount(0)
                .winRate(0.0)
                .build();

        testUserId = usersRepository.save(testUser).getUserId();
    }

    @Test
    @DisplayName("전략별 성능 비교 - 종합 리포트")
    void compareAllStrategiesPerformance() throws Exception {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("동시성 제어 전략별 성능 비교 (스레드:" + THREAD_COUNT + ", 반복:" + ITERATIONS_PER_THREAD + ")");
        System.out.println("=".repeat(80));
        System.out.printf("%-20s | %12s | %12s | %10s | %s%n",
                "전략", "총 소요시간(ms)", "평균 응답(ms)", "TPS", "정합성");
        System.out.println("-".repeat(80));

        for (LockType lockType : LockType.values()) {
            runPerformanceTest(lockType);
        }

        System.out.println("=".repeat(80) + "\n");
    }

    private void runPerformanceTest(LockType lockType) throws Exception {
        // 새 유저 생성 (각 테스트마다 초기화)
        Users testUser = Users.builder()
                .userLoginId("perf_" + lockType + "_" + System.currentTimeMillis())
                .nickname("성능테스트")
                .userRole(UserRole.USER)
                .provider(AuthProvider.LOCAL)
                .winCount(0)
                .playCount(0)
                .winRate(0.0)
                .build();
        Long userId = usersRepository.save(testUser).getUserId();

        int totalOperations = THREAD_COUNT * ITERATIONS_PER_THREAD;
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch latch = new CountDownLatch(totalOperations);
        CountDownLatch startLatch = new CountDownLatch(1);

        long startTime = System.currentTimeMillis();

        for (int t = 0; t < THREAD_COUNT; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < ITERATIONS_PER_THREAD; i++) {
                        try {
                            userStatsService.incrementPlayCount(userId, lockType);
                        } catch (Exception e) {
                            // 에러 무시 (OPTIMISTIC 재시도 실패 등)
                        } finally {
                            latch.countDown();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startLatch.countDown();
        latch.await();
        executor.shutdown();

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;
        double avgTime = (double) totalTime / totalOperations;
        double tps = (double) totalOperations / (totalTime / 1000.0);

        // 정합성 검증
        int finalPlayCount = usersRepository.findById(userId)
                .map(Users::getPlayCount)
                .orElse(0);
        boolean isConsistent = finalPlayCount == totalOperations;
        String consistencyStatus = isConsistent ? "✅ 성공" : "❌ " + finalPlayCount + "/" + totalOperations;

        System.out.printf("%-20s | %12d | %12.2f | %10.1f | %s%n",
                lockType.name(), totalTime, avgTime, tps, consistencyStatus);
    }
}
