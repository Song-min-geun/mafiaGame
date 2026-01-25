package com.example.mafiagame.game.state;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;

/**
 * 낮 투표 페이즈 상태
 */
@Component
public class DayVotingState implements GamePhaseState {

    private static final int DURATION_SECONDS = 30;

    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.DAY_VOTING);
    }

    @Override
    public int getDurationSeconds() {
        return DURATION_SECONDS;
    }

    @Override
    public GamePhaseState nextState(GameState gameState) {
        // 투표 결과에 따라 최후 변론 또는 밤으로 이동
        if (gameState.getVotedPlayerId() != null) {
            return new DayFinalDefenseState();
        }
        return new NightActionState();
    }

    @Override
    public GamePhase getGamePhase() {
        return GamePhase.DAY_VOTING;
    }
}
