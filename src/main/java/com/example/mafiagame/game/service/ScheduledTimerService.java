package com.example.mafiagame.game.service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
// import org.springframework.stereotype.Service;  // RedisTimerService와 충돌 방지를 위해 비활성화

import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.repository.GameStateRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * [레퍼런스 코드] {@code @Scheduled(fixedRate = 1000)} 기반의 JVM 인메모리 타이머 서비스.
 *
 * <p>
 * 이 클래스는 프로젝트 초기에 사용했던 타이머 구현 방식이다.
 * 매 1초마다 {@code @Scheduled}로 등록된 모든 게임의 남은 시간을 확인하고,
 * 종료 시각이 지난 게임에 대해 {@link GameService#advancePhase(String)}를 호출한다.
 * </p>
 *
 * <h3>동작 흐름</h3>
 * <ol>
 * <li>{@link #startTimer(String, long)} 호출 시 게임 ID와 종료 시각을
 * {@code activeTimers}에 등록</li>
 * <li>매 1초마다 {@link #pollAllTimers()}가 실행되어 모든 등록된 타이머를 순회</li>
 * <li>현재 시각이 종료 시각을 초과한 게임을 발견하면 {@code advancePhase}를 호출</li>
 * </ol>
 *
 * <h3>장점</h3>
 * <ul>
 * <li>구현이 매우 단순</li>
 * <li>외부 인프라(Redis 등) 의존성 없음</li>
 * <li>디버깅이 용이</li>
 * </ul>
 *
 * <h3>단점 (마이그레이션 이유)</h3>
 * <ul>
 * <li>1초 폴링으로 인해 최대 ~1초의 지연이 발생</li>
 * <li>게임 수에 비례하여 매초 순회 비용 증가 (O(N))</li>
 * <li>서버 재시작 시 모든 타이머 유실 (인메모리)</li>
 * <li>수평 확장(Scale-out) 시 동일 게임의 타이머가 여러 인스턴스에서 중복 실행</li>
 * <li>게임마다 별도 Thread를 생성하던 원래 방식은 게임 수 증가 시 스레드 폭발</li>
 * </ul>
 *
 * <p>
 * 현재는 {@link RedisTimerService} + {@link GameTimerWorker}로 대체되었다.
 * </p>
 *
 * @see RedisTimerService
 * @see GameTimerWorker
 * @see SchedulerTimerService
 */
@Slf4j
// @Service // 비활성화: 현재 RedisTimerService가 타이머 역할을 수행
public class ScheduledTimerService {

    private final GameService gameService;
    private final GameStateRepository gameStateRepository;

    /**
     * 활성 타이머 맵 (gameId → phaseEndTimeMillis).
     * <p>
     * 게임 시작/페이즈 전환 시 등록되고, 타이머 만료 또는 취소 시 제거된다.
     * </p>
     */
    private final Map<String, Long> activeTimers = new ConcurrentHashMap<>();

    public ScheduledTimerService(@Lazy GameService gameService,
            GameStateRepository gameStateRepository) {
        this.gameService = gameService;
        this.gameStateRepository = gameStateRepository;
    }

    /**
     * 매 1초마다 실행되어 등록된 모든 게임 타이머를 순회한다.
     *
     * <p>
     * 현재 시각이 종료 시각을 초과한 게임을 발견하면
     * {@link GameService#advancePhase(String)}를 호출하여 다음 페이즈로 전환한다.
     * </p>
     */
    @Scheduled(fixedRate = 1000)
    public void pollAllTimers() {
        if (activeTimers.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Set<String> gameIds = Set.copyOf(activeTimers.keySet());

        for (String gameId : gameIds) {
            Long endTime = activeTimers.get(gameId);
            if (endTime == null) {
                continue;
            }

            if (now >= endTime) {
                // 만료된 타이머 제거 후 페이즈 전환
                activeTimers.remove(gameId);
                log.info("[ScheduledTimer] 타이머 만료: gameId={}, endTime={}", gameId, endTime);

                try {
                    gameService.advancePhase(gameId);
                } catch (Exception e) {
                    log.error("[ScheduledTimer] advancePhase 실행 중 오류: gameId={}", gameId, e);
                }
            }
        }
    }

    /**
     * 타이머를 등록한다. 기존 타이머가 있으면 덮어쓴다.
     *
     * @param gameId             게임 ID
     * @param phaseEndTimeMillis 페이즈 종료 시각 (epoch millis)
     */
    public void startTimer(String gameId, long phaseEndTimeMillis) {
        if (phaseEndTimeMillis <= 0) {
            log.warn("[ScheduledTimer] 타이머 시작 실패: 종료 시간이 유효하지 않음. gameId={}", gameId);
            return;
        }

        activeTimers.put(gameId, phaseEndTimeMillis);
        log.info("[ScheduledTimer] 타이머 등록됨: gameId={}, endTime={}", gameId, phaseEndTimeMillis);
    }

    /**
     * GameState에서 phaseEndTime을 읽어 타이머를 등록한다.
     *
     * @param gameState 게임 상태
     */
    public void startTimer(GameState gameState) {
        if (gameState == null || gameState.getPhaseEndTime() == null || gameState.getPhaseEndTime() <= 0) {
            log.warn("[ScheduledTimer] 타이머 시작 실패: 종료 시간이 유효하지 않음. gameId={}",
                    gameState != null ? gameState.getGameId() : null);
            return;
        }
        startTimer(gameState.getGameId(), gameState.getPhaseEndTime());
    }

    /**
     * Redis에서 phaseEndTime을 조회하여 타이머를 등록한다.
     *
     * @param gameId 게임 ID
     */
    @Deprecated
    public void startTimer(String gameId) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null || gameState.getPhaseEndTime() == null || gameState.getPhaseEndTime() <= 0) {
            log.warn("[ScheduledTimer] 타이머 시작 실패: 게임이 없거나 종료 시간이 설정되지 않음. gameId={}", gameId);
            return;
        }
        startTimer(gameId, gameState.getPhaseEndTime());
    }

    /**
     * 지정된 게임의 타이머를 취소한다.
     *
     * @param gameId 게임 ID
     */
    public void stopTimer(String gameId) {
        Long removed = activeTimers.remove(gameId);
        if (removed != null) {
            log.info("[ScheduledTimer] 타이머 취소됨: gameId={}", gameId);
        }
    }

    /**
     * 현재 등록된 활성 타이머 수를 반환한다 (모니터링용).
     *
     * @return 활성 타이머 수
     */
    public int getActiveTimerCount() {
        return activeTimers.size();
    }

    /**
     * 특정 게임에 활성 타이머가 있는지 확인한다.
     *
     * @param gameId 게임 ID
     * @return 타이머가 등록되어 있으면 true
     */
    public boolean hasActiveTimer(String gameId) {
        return activeTimers.containsKey(gameId);
    }
}
