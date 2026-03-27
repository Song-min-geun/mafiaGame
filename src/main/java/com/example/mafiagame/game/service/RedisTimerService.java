package com.example.mafiagame.game.service;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.repository.GameTimerRepository;
import com.example.mafiagame.game.timer.GameTimerJob;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class RedisTimerService {

    private final GameStateRepository gameStateRepository;
    private final GameTimerRepository gameTimerRepository;

    public RedisTimerService(
            GameStateRepository gameStateRepository,
            GameTimerRepository gameTimerRepository) {
        this.gameStateRepository = gameStateRepository;
        this.gameTimerRepository = gameTimerRepository;
    }

    /**
     * 개별 게임마다 JVM 스케줄러를 두지 않고, Redis ZSET 대기열에 타이머를 등록한다.
     */
    public void startTimer(GameState gameState) {
        if (gameState == null || gameState.getPhaseEndTime() == null || gameState.getPhaseEndTime() <= 0) {
            log.warn("타이머 시작 실패: 종료 시간이 유효하지 않음. gameId={}",
                    gameState != null ? gameState.getGameId() : null);
            return;
        }

        String timerToken = UUID.randomUUID().toString();
        GameTimerJob timerJob = new GameTimerJob(
                gameState.getGameId(),
                gameState.getGamePhase(),
                gameState.getCurrentPhase(),
                timerToken);

        gameTimerRepository.schedule(timerJob, gameState.getPhaseEndTime());
        log.info("Redis 타이머 등록됨: gameId={}, phase={}, currentPhase={}, endTime={}",
                gameState.getGameId(), gameState.getGamePhase(), gameState.getCurrentPhase(), gameState.getPhaseEndTime());
    }

    /**
     * 테스트 및 복구용: 전달받은 종료 시각으로 Redis 타이머를 재등록한다.
     */
    public void startTimer(String gameId, long phaseEndTimeMillis) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null) {
            log.warn("타이머 시작 실패: 게임 상태가 없음. gameId={}", gameId);
            return;
        }

        gameState.setPhaseEndTime(phaseEndTimeMillis);
        gameStateRepository.save(gameState);
        startTimer(gameState);
    }

    /**
     * [Deprecated] 기존 호환용 - Redis에 저장된 종료 시간으로 재등록
     */
    @Deprecated
    public void startTimer(String gameId) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null || gameState.getPhaseEndTime() == null) {
            log.warn("타이머 시작 실패: 게임이 없거나 종료 시간이 설정되지 않음. gameId={}", gameId);
            return;
        }
        startTimer(gameState);
    }

    public void stopTimer(String gameId) {
        gameTimerRepository.stop(gameId);
        log.info("Redis 타이머 제거됨: gameId={}", gameId);
    }
}
