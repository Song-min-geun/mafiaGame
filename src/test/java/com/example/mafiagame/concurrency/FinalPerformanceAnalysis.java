package com.example.mafiagame.concurrency;

import com.example.mafiagame.global.concurrency.LockType;
import com.example.mafiagame.global.concurrency.service.UserStatsService;
import com.example.mafiagame.user.domain.AuthProvider;
import com.example.mafiagame.user.domain.UserRole;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.repository.UsersRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 동시성 제어 전략별 종합 분석 리포트
 * 
 * 이 테스트는 면접에서 보여줄 수 있는 실제 데이터를 생성합니다.
 * 
 * 측정 지표:
 * 1. 정합성 (Data Consistency) - Lost Update 발생 여부
 * 2. 처리량 (TPS) - 초당 처리 가능 요청 수
 * 3. 응답시간 (Latency) - 평균/p50/p95/p99
 * 4. 에러율 (Error Rate) - 실패한 요청 비율
 * 
 * 실행 방법:
 * ./gradlew test --tests "FinalPerformanceAnalysis.runFullAnalysis" -i
 */
@SpringBootTest
class FinalPerformanceAnalysis {

    @Autowired
    private UserStatsService userStatsService;

    @Autowired
    private UsersRepository usersRepository;

    // 테스트 설정
    private static final int THREAD_COUNT = 100; // 동시 요청 수
    private static final int ITERATIONS = 5; // 반복 횟수
    private static final int WARMUP_ITERATIONS = 2; // 워밍업

    @Test
    @DisplayName("🔥 동시성 제어 전략 종합 분석 리포트")
    void runFullAnalysis() throws Exception {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║              동시성 제어 전략별 종합 분석 리포트                                   ║");
        System.out
                .println("║                      테스트 조건: " + THREAD_COUNT + "개 스레드 동시 요청                           ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝\n");

        List<StrategyResult> results = new ArrayList<>();

        // 각 전략별 테스트 실행
        for (LockType lockType : LockType.values()) {
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            System.out.println("▶ 테스트 중: " + lockType.name());
            System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            StrategyResult result = runStrategyTest(lockType);
            results.add(result);

            System.out.println("  ✓ 완료\n");

            // 테스트 간 잠시 대기 (리소스 안정화)
            Thread.sleep(500);
        }

        // 종합 결과표 출력
        printFinalReport(results);

        // 분석 요약 출력
        printAnalysisSummary(results);
    }

    private StrategyResult runStrategyTest(LockType lockType) throws Exception {
        // ========== 1. 워밍업 (별도 유저 사용) ==========
        Users warmupUser = Users.builder()
                .userLoginId("warmup_" + lockType + "_" + System.currentTimeMillis())
                .nickname("워밍업")
                .userRole(UserRole.USER)
                .provider(AuthProvider.LOCAL)
                .build();
        Long warmupUserId = usersRepository.save(warmupUser).getUserId();

        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            runSingleIteration(warmupUserId, lockType);
        }
        Thread.sleep(200); // 안정화 대기

        // ========== 2. 실제 측정 (새 유저 사용) ==========
        Users testUser = Users.builder()
                .userLoginId("analysis_" + lockType + "_" + System.currentTimeMillis())
                .nickname("분석테스트")
                .userRole(UserRole.USER)
                .provider(AuthProvider.LOCAL)
                .winCount(0)
                .playCount(0)
                .winRate(0.0)
                .build();
        Long testUserId = usersRepository.save(testUser).getUserId();

        List<Long> latencies = new ArrayList<>();
        AtomicInteger totalSuccess = new AtomicInteger(0);
        AtomicInteger totalError = new AtomicInteger(0);
        long totalDuration = 0;

        for (int i = 0; i < ITERATIONS; i++) {
            IterationResult iterResult = runSingleIteration(testUserId, lockType);
            latencies.addAll(iterResult.latencies);
            totalSuccess.addAndGet(iterResult.successCount);
            totalError.addAndGet(iterResult.errorCount);
            totalDuration += iterResult.durationMs;
        }

        // 최종 playCount 확인
        int finalPlayCount = usersRepository.findById(testUserId)
                .map(Users::getPlayCount)
                .orElse(0);

        int expectedCount = THREAD_COUNT * ITERATIONS;
        int lostUpdates = expectedCount - finalPlayCount;
        boolean isConsistent = lostUpdates == 0;

        // 레이턴시 통계 계산
        latencies.sort(Long::compareTo);
        double avgLatency = latencies.stream().mapToLong(l -> l).average().orElse(0);
        long p50 = getPercentile(latencies, 50);
        long p95 = getPercentile(latencies, 95);
        long p99 = getPercentile(latencies, 99);

        // TPS 계산
        double tps = (double) totalSuccess.get() / (totalDuration / 1000.0);

        // 에러율 계산
        double errorRate = (double) totalError.get() / (totalSuccess.get() + totalError.get()) * 100;

        return new StrategyResult(
                lockType,
                isConsistent,
                lostUpdates,
                tps,
                avgLatency,
                p50,
                p95,
                p99,
                errorRate,
                totalSuccess.get(),
                totalError.get());
    }

    private IterationResult runSingleIteration(Long userId, LockType lockType) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(THREAD_COUNT);

        List<Long> latencies = new ArrayList<>();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    long start = System.currentTimeMillis();

                    try {
                        userStatsService.incrementPlayCount(userId, lockType);
                        long latency = System.currentTimeMillis() - start;
                        synchronized (latencies) {
                            latencies.add(latency);
                        }
                        successCount.incrementAndGet();
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startLatch.countDown();
        endLatch.await();
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();

        return new IterationResult(latencies, successCount.get(), errorCount.get(), duration);
    }

    private long getPercentile(List<Long> sortedList, int percentile) {
        if (sortedList.isEmpty())
            return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sortedList.size()) - 1;
        return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
    }

    private void printFinalReport(List<StrategyResult> results) {
        System.out.println("\n");
        System.out.println(
                "╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println(
                "║                                    📊 최종 비교 결과표                                                          ║");
        System.out.println(
                "╠════════════════════╦═══════════╦════════════╦══════════╦═════════╦═════════╦═════════╦══════════╦══════════════╣");
        System.out.println(
                "║       전략          ║  정합성    ║ Lost Update ║   TPS    ║ Avg(ms) ║ p50(ms) ║ p95(ms) ║ p99(ms)  ║  Error Rate  ║");
        System.out.println(
                "╠════════════════════╬═══════════╬════════════╬══════════╬═════════╬═════════╬═════════╬══════════╬══════════════╣");

        for (StrategyResult r : results) {
            String consistency = r.isConsistent ? "  ✅  " : "  ❌  ";
            System.out.printf("║ %-18s ║ %s ║ %10d ║ %8.1f ║ %7.1f ║ %7d ║ %7d ║ %8d ║ %10.2f%% ║%n",
                    r.lockType.name(),
                    consistency,
                    r.lostUpdates,
                    r.tps,
                    r.avgLatency,
                    r.p50,
                    r.p95,
                    r.p99,
                    r.errorRate);
        }

        System.out.println(
                "╚════════════════════╩═══════════╩════════════╩══════════╩═════════╩═════════╩═════════╩══════════╩══════════════╝");
        System.out.println("\n테스트 조건: " + THREAD_COUNT + "개 스레드 × " + ITERATIONS + "회 반복 = "
                + (THREAD_COUNT * ITERATIONS) + "회 요청/전략");
    }

    private void printAnalysisSummary(List<StrategyResult> results) {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                           📝 분석 요약 및 결론                                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════╝");

        // 정합성 실패 전략 확인
        System.out.println("\n1️⃣ 정합성 분석:");
        for (StrategyResult r : results) {
            if (!r.isConsistent) {
                System.out.printf("   ❌ %s: %d개 Lost Update 발생 (%.1f%% 손실)%n",
                        r.lockType.name(), r.lostUpdates,
                        (double) r.lostUpdates / (THREAD_COUNT * ITERATIONS) * 100);
            } else {
                System.out.printf("   ✅ %s: 정합성 보장%n", r.lockType.name());
            }
        }

        // TPS 순위
        System.out.println("\n2️⃣ TPS 순위 (높을수록 좋음):");
        results.stream()
                .filter(r -> r.isConsistent)
                .sorted((a, b) -> Double.compare(b.tps, a.tps))
                .forEach(r -> System.out.printf("   • %s: %.1f TPS%n", r.lockType.name(), r.tps));

        // p99 레이턴시 순위
        System.out.println("\n3️⃣ p99 Latency 순위 (낮을수록 좋음):");
        results.stream()
                .filter(r -> r.isConsistent)
                .sorted((a, b) -> Long.compare(a.p99, b.p99))
                .forEach(r -> System.out.printf("   • %s: %dms%n", r.lockType.name(), r.p99));

        // 최종 추천
        System.out.println("\n4️⃣ 최종 추천:");
        StrategyResult best = results.stream()
                .filter(r -> r.isConsistent)
                .max((a, b) -> Double.compare(a.tps, b.tps))
                .orElse(null);

        if (best != null) {
            System.out.printf("   🏆 추천 전략: %s%n", best.lockType.name());
            System.out.printf("   • TPS: %.1f%n", best.tps);
            System.out.printf("   • p99 Latency: %dms%n", best.p99);
            System.out.println("   • 이유: 정합성 보장 + 최고 TPS");
        }

        System.out.println("\n" + "═".repeat(80) + "\n");
    }

    // 내부 클래스
    private static class IterationResult {
        List<Long> latencies;
        int successCount;
        int errorCount;
        long durationMs;

        IterationResult(List<Long> latencies, int successCount, int errorCount, long durationMs) {
            this.latencies = latencies;
            this.successCount = successCount;
            this.errorCount = errorCount;
            this.durationMs = durationMs;
        }
    }

    private static class StrategyResult {
        LockType lockType;
        boolean isConsistent;
        int lostUpdates;
        double tps;
        double avgLatency;
        long p50;
        long p95;
        long p99;
        double errorRate;
        int totalSuccess;
        int totalError;

        StrategyResult(LockType lockType, boolean isConsistent, int lostUpdates, double tps,
                double avgLatency, long p50, long p95, long p99, double errorRate,
                int totalSuccess, int totalError) {
            this.lockType = lockType;
            this.isConsistent = isConsistent;
            this.lostUpdates = lostUpdates;
            this.tps = tps;
            this.avgLatency = avgLatency;
            this.p50 = p50;
            this.p95 = p95;
            this.p99 = p99;
            this.errorRate = errorRate;
            this.totalSuccess = totalSuccess;
            this.totalError = totalError;
        }
    }
}
