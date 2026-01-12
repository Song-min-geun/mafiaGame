package com.example.mafiagame.game.controller;

import com.example.mafiagame.game.service.lock.ConcurrencyComparisonTest;
import com.example.mafiagame.game.service.lock.LockVoteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시성 제어 방식 비교 테스트 API
 * 
 * 개발/테스트 환경에서만 사용
 */
@RestController
@RequestMapping("/api/test/concurrency")
@RequiredArgsConstructor
@Slf4j
public class ConcurrencyTestController {

    private final ConcurrencyComparisonTest comparisonTest;
    private final LockVoteService lockVoteService;

    /**
     * 동시성 비교 테스트 실행 (단일 게임)
     */
    @PostMapping("/compare")
    public ResponseEntity<?> runComparison(
            @RequestParam(defaultValue = "8") int playerCount) {
        String testGameId = "test_game_" + System.currentTimeMillis();

        log.info("동시성 비교 테스트 시작: gameId={}, playerCount={}", testGameId, playerCount);
        comparisonTest.runComparison(testGameId, Math.min(playerCount, 8));

        return ResponseEntity.ok(Map.of(
                "message", "테스트 완료 - 로그를 확인하세요",
                "gameId", testGameId));
    }

    /**
     * 다중 게임 스트레스 테스트
     * 
     * 예: POST
     * /api/test/concurrency/stress?gameCount=100&playerCount=8&lockType=LUA_SCRIPT
     */
    @PostMapping("/stress")
    public ResponseEntity<?> runStressTest(
            @RequestParam(defaultValue = "50") int gameCount,
            @RequestParam(defaultValue = "8") int playerCount,
            @RequestParam(defaultValue = "LUA_SCRIPT") LockVoteService.LockType lockType) {

        log.info("========== 다중 게임 스트레스 테스트 시작 ==========");
        log.info("게임 수: {}, 게임당 플레이어: {}, 락 타입: {}", gameCount, playerCount, lockType);

        int actualPlayerCount = Math.min(playerCount, 8);
        int totalVotes = gameCount * actualPlayerCount;

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(gameCount * actualPlayerCount, 200));
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalVotes);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();

        // 각 게임의 게임 상태 생성
        List<String> gameIds = new ArrayList<>();
        for (int g = 0; g < gameCount; g++) {
            String gameId = "stress_game_" + System.currentTimeMillis() + "_" + g;
            gameIds.add(gameId);
            comparisonTest.prepareTestGameStatePublic(gameId, actualPlayerCount);
        }

        // 모든 게임의 모든 플레이어가 동시 투표
        for (String gameId : gameIds) {
            for (int p = 1; p <= actualPlayerCount; p++) {
                final String voterId = "player" + p;
                final String targetId = "player" + ((p % actualPlayerCount) + 1);

                executor.submit(() -> {
                    try {
                        startLatch.await();

                        long start = System.nanoTime();
                        boolean success = lockVoteService.vote(gameId, voterId, targetId, lockType);
                        long elapsed = (System.nanoTime() - start) / 1_000_000;

                        latencies.add(elapsed);
                        if (success) {
                            successCount.incrementAndGet();
                        } else {
                            failCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failCount.incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }
        }

        long totalStart = System.nanoTime();
        startLatch.countDown(); // 모든 스레드 시작!

        try {
            boolean completed = endLatch.await(60, TimeUnit.SECONDS);
            if (!completed) {
                log.error("테스트 타임아웃!");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalTime = (System.nanoTime() - totalStart) / 1_000_000;
        executor.shutdown();

        // 결과 계산
        long avgLatency = latencies.isEmpty() ? 0
                : (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxLatency = latencies.isEmpty() ? 0 : latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        long minLatency = latencies.isEmpty() ? 0 : latencies.stream().mapToLong(Long::longValue).min().orElse(0);
        double throughput = totalVotes / (totalTime / 1000.0); // votes per second

        // 정확성 검증: 각 게임의 투표 수 확인
        int totalFinalVotes = 0;
        int correctGames = 0;
        for (String gameId : gameIds) {
            int voteCount = comparisonTest.getVoteCount(gameId);
            totalFinalVotes += voteCount;
            if (voteCount == actualPlayerCount) {
                correctGames++;
            }
        }

        log.info("========== 스트레스 테스트 결과 ==========");
        log.info("락 타입: {}", lockType);
        log.info("게임 수: {}, 총 투표 수: {}", gameCount, totalVotes);
        log.info("성공: {}, 실패: {}", successCount.get(), failCount.get());
        log.info("정확한 게임 수: {}/{} ({}%)", correctGames, gameCount,
                (correctGames * 100 / gameCount));
        log.info("총 소요 시간: {}ms", totalTime);
        log.info("지연 시간 - 평균: {}ms, 최소: {}ms, 최대: {}ms", avgLatency, minLatency, maxLatency);
        log.info("처리량: {} votes/sec", String.format("%.2f", throughput));
        log.info("==========================================");

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("lockType", lockType.name());
        result.put("gameCount", gameCount);
        result.put("totalVotes", totalVotes);
        result.put("successCount", successCount.get());
        result.put("failCount", failCount.get());
        result.put("correctGames", correctGames);
        result.put("correctGamePercent", (correctGames * 100 / gameCount));
        result.put("totalTimeMs", totalTime);
        result.put("avgLatencyMs", avgLatency);
        result.put("maxLatencyMs", maxLatency);
        result.put("throughputPerSec", String.format("%.2f", throughput));

        return ResponseEntity.ok(result);
    }

    /**
     * 개별 방식 투표 테스트
     */
    @PostMapping("/vote")
    public ResponseEntity<?> testVote(
            @RequestParam String gameId,
            @RequestParam String voterId,
            @RequestParam String targetId,
            @RequestParam LockVoteService.LockType lockType) {
        long start = System.nanoTime();
        boolean success = lockVoteService.vote(gameId, voterId, targetId, lockType);
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        return ResponseEntity.ok(Map.of(
                "success", success,
                "lockType", lockType,
                "elapsedMs", elapsed));
    }
}
