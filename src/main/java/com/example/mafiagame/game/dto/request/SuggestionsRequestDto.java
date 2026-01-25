package com.example.mafiagame.game.dto.request;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.PlayerRole;

public record SuggestionsRequestDto(
        PlayerRole role,
        GamePhase phase,
        String gameId) {
}
