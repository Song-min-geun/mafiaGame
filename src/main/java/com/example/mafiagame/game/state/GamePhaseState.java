package com.example.mafiagame.game.state;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.service.PhaseResultProcessor;

/**
 * 게임 페이즈 상태 인터페이스 (State Pattern)
 */
public interface GamePhaseState {

    /**
     * 현재 페이즈 진입 시 처리
     * 
     * @param gameState 게임 상태
     */
    void process(GameState gameState);

    /**
     * 현재 페이즈 종료 시 결과 처리 (투표 집계, 밤 행동 처리 등)
     * advancePhase에서 다음 페이즈로 넘어가기 전에 호출됨
     *
     * @param gameState 게임 상태
     * @param processor 결과 처리를 위임할 프로세서
     */
    void onExit(GameState gameState, PhaseResultProcessor processor);

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
    GamePhase getGamePhase();
}
