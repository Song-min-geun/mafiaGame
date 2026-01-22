package com.example.mafiagame.game.dto.request;

import java.util.List;

public record CreateGameRequest(
        String roomId,
        String roomName,
        List<PlayerData> players) {
    public record PlayerData(String playerId, String nickname) {
    }
}
