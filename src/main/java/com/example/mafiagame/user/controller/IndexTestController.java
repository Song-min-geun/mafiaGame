package com.example.mafiagame.user.controller;

import com.example.mafiagame.user.service.IndexPerformanceTest;
import com.example.mafiagame.user.service.IndexPerformanceTest.PerformanceResult;
import lombok.RequiredArgsConstructor;

import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 인덱스 성능 테스트용 API 컨트롤러
 */
@RestController
@RequestMapping("/api/test/index")
@RequiredArgsConstructor
@Profile("test")
public class IndexTestController {

    private final IndexPerformanceTest indexPerformanceTest;

    /**
     * 테스트용 더미 유저 생성
     * 
     * @param count 생성할 유저 수 (기본값: 10000)
     */
    @PostMapping("/prepare")
    public ResponseEntity<Map<String, Object>> prepareTestData(
            @RequestParam(defaultValue = "10000") int count) {

        long startTime = System.currentTimeMillis();
        indexPerformanceTest.createDummyUsersForTest(count);
        long elapsed = System.currentTimeMillis() - startTime;

        return ResponseEntity.ok(Map.of(
                "message", "더미 유저 " + count + " 명 생성 완료",
                "elapsedMs", elapsed));
    }

    /**
     * 랭킹 쿼리 성능 테스트 실행
     * 
     * @param iterations 반복 횟수 (기본값: 10)
     */
    @GetMapping("/run")
    public ResponseEntity<PerformanceResult> runPerformanceTest(
            @RequestParam(defaultValue = "10") int iterations) {

        PerformanceResult result = indexPerformanceTest.measureRankingQuery(iterations);
        return ResponseEntity.ok(result);
    }
}
