package com.example.mafiagame.game.state;

import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GameState;
import org.springframework.stereotype.Component;

/**
 * 밤 행동 페이즈 상태
 */
@Component
public class NightActionState implements GamePhaseState {

    private static final int DURATION_SECONDS = 30;

    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.NIGHT_ACTION);
        gameState.getNightActions().clear();
    }

    @Override
    public int getDurationSeconds() {
        return DURATION_SECONDS;
    }

    @Override
    public GamePhaseState nextState(GameState gameState) {
        gameState.setCurrentPhase(gameState.getCurrentPhase() + 1);
        return new DayDiscussionState();
    }
}
