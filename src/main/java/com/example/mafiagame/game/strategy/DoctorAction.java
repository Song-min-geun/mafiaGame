package com.example.mafiagame.game.strategy;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.PlayerRole;

/**
 * 의사 밤 행동 전략
 * - 타겟을 지목하여 마피아 공격으로부터 보호
 */
@Component
public class DoctorAction implements RoleActionStrategy {

    @Override
    public NightActionResult execute(GameState gameState, GamePlayerState actor, GamePlayerState target) {
        return NightActionResult.builder()
                .actorRole(PlayerRole.DOCTOR)
                .actorId(actor.getPlayerId())
                .targetId(target.getPlayerId())
                .message(actor.getPlayerName() + "이(가) " + target.getPlayerName() + "을(를) 보호합니다.")
                .success(true)
                .build();
    }
}
