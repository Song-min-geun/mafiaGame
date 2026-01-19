package com.example.mafiagame.game.state;

import com.example.mafiagame.game.domain.GameState;

/**
 * 게임 페이즈 상태 인터페이스 (State Pattern)
 */
public interface GamePhaseState {

    /**
     * 현재 페이즈 처리
     * 
     * @param gameState 게임 상태
     */
    void process(GameState gameState);

    /**
     * 페이즈 지속 시간 (초)
     */
    int getDurationSeconds();

    /**
     * 다음 페이즈로 전환
     * 
     * @param gameState 게임 상태
     * @return 다음 상태
     */
    GamePhaseState nextState(GameState gameState);

    /**
     * 현재 페이즈 종류 반환
     */
    com.example.mafiagame.game.domain.GamePhase getGamePhase();
}
