package com.example.mafiagame.game.strategy;

import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.GameState;

/**
 * 역할별 밤 행동 전략 인터페이스 (Strategy Pattern)
 */
public interface RoleActionStrategy {

    /**
     * 밤 행동 실행
     * 
     * @param gameState 현재 게임 상태
     * @param actor     행동 주체
     * @param target    행동 대상
     * @return 행동 결과 (마피아 타겟, 의사 보호 대상 등)
     */
    NightActionResult execute(GameState gameState, GamePlayerState actor, GamePlayerState target);
}
