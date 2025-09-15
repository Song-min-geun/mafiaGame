package com.example.mafiagame.game.dto.request;

public record ExtendTimeRequest(
        String gameId,
        String playerId,
        Integer seconds
) {
}
