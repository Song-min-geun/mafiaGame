package com.example.mafiagame.game.state;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;

/**
 * 낮 토론 페이즈 상태
 */
@Component
public class DayDiscussionState implements GamePhaseState {

    private static final int DURATION_SECONDS = 60;

    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.DAY_DISCUSSION);
    }

    @Override
    public int getDurationSeconds() {
        return DURATION_SECONDS;
    }

    @Override
    public GamePhaseState nextState(GameState gameState) {
        return new DayVotingState();
    }

    @Override
    public GamePhase getGamePhase() {
        return GamePhase.DAY_DISCUSSION;
    }
}
