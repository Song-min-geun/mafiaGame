package com.example.mafiagame.game.strategy;

import org.springframework.stereotype.Component;

import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.PlayerRole;

/**
 * 경찰 밤 행동 전략
 * - 타겟을 지목하여 마피아 여부 확인
 */
@Component
public class PoliceAction implements RoleActionStrategy {

    @Override
    public NightActionResult execute(GameState gameState, GamePlayerState actor, GamePlayerState target) {
        boolean isMafia = target.getRole() == PlayerRole.MAFIA;
        String resultMessage = String.format("경찰 조사 결과: %s님은 [ %s ] 입니다.",
                target.getPlayerName(), isMafia ? "마피아" : "시민");

        return NightActionResult.builder()
                .actorRole(PlayerRole.POLICE)
                .actorId(actor.getPlayerId())
                .targetId(target.getPlayerId())
                .message(resultMessage)
                .success(true)
                .build();
    }
}
