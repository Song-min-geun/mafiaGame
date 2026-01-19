package com.example.mafiagame.game.dto.request;

import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.domain.GamePhase;

public record SuggestionsRequestDto(
                PlayerRole role,
                GamePhase phase,
                String gameId) {
}
