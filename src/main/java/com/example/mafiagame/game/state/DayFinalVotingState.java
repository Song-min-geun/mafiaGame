package com.example.mafiagame.game.state;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;

/**
 * 최종 투표 (찬반) 페이즈 상태
 */
@Component
public class DayFinalVotingState implements GamePhaseState {

    private static final int DURATION_SECONDS = 20;

    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.DAY_FINAL_VOTING);
    }

    @Override
    public int getDurationSeconds() {
        return DURATION_SECONDS;
    }

    @Override
    public GamePhaseState nextState(GameState gameState) {
        return new NightActionState();
    }

    @Override
    public GamePhase getGamePhase() {
        return GamePhase.DAY_FINAL_VOTING;
    }
}
