package com.example.mafiagame.user.service;

import com.example.mafiagame.user.dto.reponse.Top10UserResponse;
import com.example.mafiagame.user.repository.UsersRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 인덱스 성능 테스트 서비스
 * 
 * idx_ranking 인덱스의 효과를 측정하기 위한 테스트 도구
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IndexPerformanceTest {

    private final UsersRepository userRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 테스트용 더미 유저 대량 생성 (Native SQL 사용 - 고속)
     * 
     * @param count 생성할 유저 수
     */
    @Transactional
    public void createDummyUsersForTest(int count) {
        log.info("========== 더미 유저 {} 명 생성 시작 (Native SQL) ==========", count);

        long startTime = System.currentTimeMillis();
        Random random = new Random();
        int batchSize = 5000;

        StringBuilder sb = new StringBuilder();
        int batchCount = 0;

        for (int i = 1; i <= count; i++) {
            int playCount = random.nextInt(201); // 0 ~ 200
            int winCount = playCount > 0 ? random.nextInt(playCount + 1) : 0;
            double winRate = playCount > 0 ? (double) winCount / playCount : 0.0;

            String loginId = "test" + System.nanoTime() + "_" + i;
            String nickname = "테스트" + i;

            if (sb.length() == 0) {
                sb.append(
                        "INSERT INTO users (user_login_id, user_login_password, nickname, user_role, provider, play_count, win_count, win_rate) VALUES ");
            } else {
                sb.append(",");
            }

            sb.append(String.format("('%s','password','%s','USER','LOCAL',%d,%d,%.4f)",
                    loginId, nickname, playCount, winCount, winRate));

            batchCount++;

            // 배치 실행
            if (batchCount >= batchSize) {
                entityManager.createNativeQuery(sb.toString()).executeUpdate();
                sb.setLength(0);
                batchCount = 0;

                if (i % 100000 == 0) {
                    log.info("진행률: {}/{} ({}%)", i, count, (i * 100) / count);
                }
            }
        }

        // 남은 데이터 저장
        if (sb.length() > 0) {
            entityManager.createNativeQuery(sb.toString()).executeUpdate();
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("========== 더미 유저 생성 완료 ({} ms, {} 건/초) ==========",
                elapsed, (count * 1000L) / Math.max(1, elapsed));
    }

    /**
     * findTopRanking 쿼리 성능 측정
     * 
     * @param iterations 반복 횟수
     * @return 성능 측정 결과
     */
    @Transactional(readOnly = true)
    public PerformanceResult measureRankingQuery(int iterations) {
        log.info("========== 랭킹 쿼리 성능 테스트 시작 ({}회 반복) ==========", iterations);

        List<Long> latencies = new ArrayList<>();

        // 워밍업 (첫 실행은 캐시 등의 영향이 있을 수 있음)
        userRepository.findTopRanking(PageRequest.of(0, 10));

        for (int i = 1; i <= iterations; i++) {
            long start = System.nanoTime();
            List<Top10UserResponse> result = userRepository.findTopRanking(PageRequest.of(0, 10));
            long elapsed = (System.nanoTime() - start) / 1_000_000; // ms

            latencies.add(elapsed);
            log.debug("반복 {}: {}ms (결과 {}건)", i, elapsed, result.size());
        }

        long avgLatency = (long) latencies.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxLatency = latencies.stream().mapToLong(Long::longValue).max().orElse(0);
        long minLatency = latencies.stream().mapToLong(Long::longValue).min().orElse(0);

        PerformanceResult result = new PerformanceResult(
                iterations,
                avgLatency,
                minLatency,
                maxLatency,
                userRepository.count());

        log.info("---------- 성능 테스트 결과 ----------");
        log.info("총 유저 수: {}", result.totalUsers());
        log.info("반복 횟수: {}", result.iterations());
        log.info("평균 응답 시간: {} ms", result.avgLatencyMs());
        log.info("최소 응답 시간: {} ms", result.minLatencyMs());
        log.info("최대 응답 시간: {} ms", result.maxLatencyMs());
        log.info("=====================================");

        return result;
    }

    /**
     * 성능 측정 결과
     */
    public record PerformanceResult(
            int iterations,
            long avgLatencyMs,
            long minLatencyMs,
            long maxLatencyMs,
            long totalUsers) {
    }
}
