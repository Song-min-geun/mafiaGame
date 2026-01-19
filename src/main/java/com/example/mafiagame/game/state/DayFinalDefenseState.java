package com.example.mafiagame.game.state;

import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GameState;
import org.springframework.stereotype.Component;

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
