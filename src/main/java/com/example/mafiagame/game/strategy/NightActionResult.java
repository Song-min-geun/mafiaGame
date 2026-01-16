package com.example.mafiagame.game.strategy;

import com.example.mafiagame.game.domain.PlayerRole;
import lombok.Builder;
import lombok.Getter;

/**
 * 밤 행동 결과 DTO
 */
@Getter
@Builder
public class NightActionResult {
    private final PlayerRole actorRole;
    private final String actorId;
    private final String targetId;
    private final String message; // 결과 메시지 (경찰 조사 결과 등)
    private final boolean success;
}
