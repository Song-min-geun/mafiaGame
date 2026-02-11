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

    /**
     * Advances the game into the day discussion phase and prepares state for the new day.
     *
     * Sets the game's phase to DAY_DISCUSSION, increments the current phase counter by one,
     * and clears any stored night actions.
     *
     * @param gameState the mutable game state to update
     */
    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.DAY_DISCUSSION);
        // 다음 날로 진행 (이전 toNextDayPhase에서 하던 작업)
        gameState.setCurrentPhase(gameState.getCurrentPhase() + 1);
        gameState.getNightActions().clear();
    }

    /**
     * Get the duration of the day discussion phase.
     *
     * @return the duration of the day discussion phase in seconds (60).
     */
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