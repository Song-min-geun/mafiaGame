package com.example.mafiagame.game.state;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;

/**
 * 밤 행동 페이즈 상태
 */
@Component
public class NightActionState implements GamePhaseState {

    private static final int DURATION_SECONDS = 30;

    @Override
    public void process(GameState gameState) {
        gameState.setGamePhase(GamePhase.NIGHT_ACTION);
        // 낮 페이즈 데이터 정리 (이전 toNightPhase에서 하던 작업)
        gameState.getVotes().clear();
        gameState.getFinalVotes().clear();
        gameState.setVotedPlayerId(null);
        gameState.getNightActions().clear();
    }

    @Override
    public int getDurationSeconds() {
        return DURATION_SECONDS;
    }

    @Override
    public GamePhaseState nextState(GameState gameState) {
        // currentPhase 증가는 DayDiscussionState.process()에서 처리
        return new DayDiscussionState();
    }

    @Override
    public GamePhase getGamePhase() {
        return GamePhase.NIGHT_ACTION;
    }
}
