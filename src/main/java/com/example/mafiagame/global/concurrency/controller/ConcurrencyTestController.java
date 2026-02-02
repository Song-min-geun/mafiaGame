package com.example.mafiagame.global.concurrency.controller;

import com.example.mafiagame.global.concurrency.LockType;
import com.example.mafiagame.global.concurrency.service.UserStatsService;
import com.example.mafiagame.user.domain.Users;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 동시성 제어 A/B 테스트용 컨트롤러
 * 
 * 사용법:
 * POST /api/test/concurrency/increment?userId=1&lockType=REDISSON_PUBSUB
 * 
 * 지원하는 lockType:
 * - NONE: 락 없음 (대조군, Lost Update 발생 예상)
 * - SYNCHRONIZED: Java synchronized (단일 JVM에서만 동작)
 * - PESSIMISTIC: DB Pessimistic Lock (SELECT ... FOR UPDATE)
 * - OPTIMISTIC: DB Optimistic Lock (@Version)
 * - REDISSON_SPIN: Redis Spin Lock (Polling)
 * - REDISSON_PUBSUB: Redis Pub-Sub Lock (효율적 대기)
 * 
 * 활용 시나리오:
 * - nGrinder/k6에서 lockType 파라미터만 변경하여 즉시 비교 테스트 가능
 * - 면접 시연에서 실시간으로 락 방식별 성능 차이 시연 가능
 */
@RestController
@RequestMapping("/api/test/concurrency")
@RequiredArgsConstructor
@Slf4j
public class ConcurrencyTestController {

    private final UserStatsService userStatsService;

    /**
     * 유저 playCount 증가 (동시성 제어 테스트)
     * 
     * @param userId   테스트 대상 유저 ID
     * @param lockType 사용할 락 타입 (기본값: NONE)
     * @return 성공 메시지
     */
    @PostMapping("/increment")
    public ResponseEntity<Map<String, Object>> incrementPlayCount(
            @RequestParam Long userId,
            @RequestParam(defaultValue = "NONE") LockType lockType) {

        long startTime = System.currentTimeMillis();

        try {
            userStatsService.incrementPlayCount(userId, lockType);

            long elapsedTime = System.currentTimeMillis() - startTime;

            return ResponseEntity.ok(Map.of(
                    "status", "SUCCESS",
                    "lockType", lockType.name(),
                    "userId", userId,
                    "elapsedMs", elapsedTime));

        } catch (Exception e) {
            log.error("[동시성 테스트] 실패 - userId={}, lockType={}, error={}",
                    userId, lockType, e.getMessage(), e);

            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "ERROR",
                    "lockType", lockType.name(),
                    "userId", userId,
                    "error", errorMessage));
        }
    }

    /**
     * 유저 전적 조회
     */
    @GetMapping("/stats/{userId}")
    public ResponseEntity<?> getStats(@PathVariable Long userId) {
        Users user = userStatsService.getStats(userId);

        if (user == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "userId", user.getUserId(),
                "playCount", user.getPlayCount(),
                "winCount", user.getWinCount(),
                "winRate", user.getWinRate()));
    }

    /**
     * 현재 playCount만 조회 (간단한 검증용)
     */
    @GetMapping("/playcount/{userId}")
    public ResponseEntity<?> getPlayCount(@PathVariable Long userId) {
        Integer playCount = userStatsService.getPlayCount(userId);

        if (playCount == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "playCount", playCount));
    }
}
