package com.example.mafiagame.game.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.repository.GameTimerRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class GameTimerRecoveryService {

    private final GameStateRepository gameStateRepository;
    private final GameTimerRepository gameTimerRepository;
    private final RedisTimerService redisTimerService;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverTimers() {
        for (GameState gameState : gameStateRepository.findInProgressGames()) {
            if (gameTimerRepository.hasScheduledTimer(gameState.getGameId())) {
                continue;
            }

            redisTimerService.startTimer(gameState);
            log.warn("[GameTimerRecovery] missing timer recovered: gameId={}, phase={}, currentPhase={}, phaseEndTime={}",
                    gameState.getGameId(),
                    gameState.getGamePhase(),
                    gameState.getCurrentPhase(),
                    gameState.getPhaseEndTime());
        }
    }
}
