package com.example.mafiagame.game.service.lock;

import com.example.mafiagame.game.domain.*;
import com.example.mafiagame.game.repository.GameStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동시성 제어 방식 비교 테스트
 * 
 * 각 방식(낙관적 락, 분산 락, Lua Script)으로 동시 투표를 실행하고
 * 성능과 정확성을 비교합니다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConcurrencyComparisonTest {

    private final LockVoteService lockVoteService;
    private final GameStateRepository gameStateRepository;

    /**
     * 동시성 비교 테스트 실행
     * 
     * @param gameId      테스트할 게임 ID
     * @param playerCount 동시 투표할 플레이어 수 (최대 8명)
     */
    public void runComparison(String gameId, int playerCount) {
        log.info("========== 동시성 비교 테스트 시작 ==========");
        log.info("게임 ID: {}, 플레이어 수: {}", gameId, playerCount);

        // 테스트용 게임 상태 생성
        prepareTestGameState(gameId, playerCount);

        // 각 방식별 테스트
        for (LockVoteService.LockType lockType : LockVoteService.LockType.values()) {
            resetVotes(gameId);
            ComparisonResult result = testConcurrentVotes(gameId, playerCount, lockType);
            printResult(lockType, result);
        }

        log.info("========== 동시성 비교 테스트 완료 ==========");
    }

    private void prepareTestGameState(String gameId, int playerCount) {
        prepareTestGameStatePublic(gameId, playerCount);
    }

    /**
     * 스트레스 테스트용 public 메서드
     */
    public void prepareTestGameStatePublic(String gameId, int playerCount) {
        List<GamePlayerState> players = new ArrayList<>();
        for (int i = 1; i <= playerCount; i++) {
            players.add(GamePlayerState.builder()
                    .playerId("player" + i)
                    .playerName("플레이어" + i)
                    .isAlive(true)
                    .role(i == 1 ? PlayerRole.MAFIA : PlayerRole.CITIZEN)
                    .team(i == 1 ? Team.MAFIA : Team.CITIZEN)
                    .build());
        }

        GameState gameState = GameState.builder()
                .gameId(gameId)
                .roomId("test-room")
                .roomName("테스트 방")
                .status(GameStatus.IN_PROGRESS)
                .gamePhase(GamePhase.DAY_VOTING)
                .currentPhase(1)
                .players(players)
                .votes(new ArrayList<>())
                .build();

        gameStateRepository.save(gameState);
    }

    /**
     * 게임의 현재 투표 수 조회
     */
    public int getVoteCount(String gameId) {
        return gameStateRepository.findById(gameId)
                .map(state -> state.getVotes().size())
                .orElse(0);
    }

    private void resetVotes(String gameId) {
        gameStateRepository.findById(gameId).ifPresent(state -> {
            state.getVotes().clear();
            state.setGamePhase(GamePhase.DAY_VOTING);
            gameStateRepository.save(state);
        });
    }

    private ComparisonResult testConcurrentVotes(String gameId, int playerCount, LockVoteService.LockType lockType) {
        ExecutorService executor = Executors.newFixedThreadPool(playerCount);
        CountDownLatch startLatch = new CountDownLatch(1); // 동시 시작을 위한 래치
        CountDownLatch endLatch = new CountDownLatch(playerCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);
        List<Long> latencies = new CopyOnWriteArrayList<>();

        // 각 플레이어가 동시에 투표
        for (int i = 1; i <= playerCount; i++) {
            final String voterId = "player" + i;
            final String targetId = "player" + ((i % playerCount) + 1); // 다음 플레이어에게 투표

            executor.submit(() -> {
                try {
                    startLatch.await(); // 모든 스레드가 동시에 시작

                    long start = System.nanoTime();
                    boolean success = lockVoteService.vote(gameId, voterId, targetId, lockType);
                    long elapsed = (System.nanoTime() - start) / 1_000_000; // ms

                    latencies.add(elapsed);
                    if (success) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.error("투표 실패: {}", e.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        long totalStart = System.nanoTime();
        startLatch.countDown(); // 모든 스레드 시작!

        try {
            endLatch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        long totalTime = (System.nanoTime() - totalStart) / 1_000_000;
        executor.shutdown();

        // 최종 투표 수 확인
        int finalVoteCount = gameStateRepository.findById(gameId)
                .map(state -> state.getVotes().size())
                .orElse(0);

        return new ComparisonResult(
                lockType,
                successCount.get(),
                failCount.get(),
                finalVoteCount,
                playerCount,
                totalTime,
                calculateAvg(latencies),
                calculateMax(latencies));
    }

    private void printResult(LockVoteService.LockType lockType, ComparisonResult result) {
        log.info("---------- {} 결과 ----------", lockType);
        log.info("성공: {}/{}, 실패: {}", result.successCount, result.expectedVotes, result.failCount);
        log.info("최종 투표 수: {} (예상: {})", result.finalVoteCount, result.expectedVotes);
        log.info("총 소요 시간: {}ms", result.totalTimeMs);
        log.info("평균 지연: {}ms, 최대 지연: {}ms", result.avgLatencyMs, result.maxLatencyMs);
        log.info("정확성: {}", result.finalVoteCount == result.expectedVotes ? "✅ 통과" : "❌ 실패 (데이터 손실)");
    }

    private long calculateAvg(List<Long> values) {
        return values.isEmpty() ? 0 : (long) values.stream().mapToLong(Long::longValue).average().orElse(0);
    }

    private long calculateMax(List<Long> values) {
        return values.isEmpty() ? 0 : values.stream().mapToLong(Long::longValue).max().orElse(0);
    }

    public record ComparisonResult(
            LockVoteService.LockType lockType,
            int successCount,
            int failCount,
            int finalVoteCount,
            int expectedVotes,
            long totalTimeMs,
            long avgLatencyMs,
            long maxLatencyMs) {
    }
}
