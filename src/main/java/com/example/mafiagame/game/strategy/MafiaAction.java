package com.example.mafiagame.game.strategy;

import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.PlayerRole;
import org.springframework.stereotype.Component;

/**
 * 마피아 밤 행동 전략
 * - 타겟을 지목하여 제거 투표
 */
@Component
public class MafiaAction implements RoleActionStrategy {

    @Override
    public NightActionResult execute(GameState gameState, GamePlayerState actor, GamePlayerState target) {
        return NightActionResult.builder()
                .actorRole(PlayerRole.MAFIA)
                .actorId(actor.getPlayerId())
                .targetId(target.getPlayerId())
                .message(actor.getPlayerName() + "이(가) " + target.getPlayerName() + "을(를) 지목했습니다.")
                .success(true)
                .build();
    }
}
