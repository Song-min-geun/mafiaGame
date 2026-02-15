package com.example.mafiagame.game.state;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.service.PhaseResultProcessor;

/**
 * 최후 변론 페이즈 상태
 */
@Component
public class DayFinalDefenseState implements GamePhaseState {

    private static final int DURATION_SECONDS = 20;

    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.DAY_FINAL_DEFENSE);
    }

    @Override
    public void onExit(GameState gameState, PhaseResultProcessor processor) {
        // 최후 변론 종료 시 별도 처리 없음
    }

    @Override
    public int getDurationSeconds() {
        return DURATION_SECONDS;
    }

    @Override
    public GamePhaseState nextState(GameState gameState) {
        return new DayFinalVotingState();
    }

    @Override
    public GamePhase getGamePhase() {
        return GamePhase.DAY_FINAL_DEFENSE;
    }
}
