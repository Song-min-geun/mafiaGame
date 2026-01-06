package com.example.mafiagame.game.dto.request;

import java.util.List;

public record CreateGameRequest(String roomId, List<PlayerData> players) {
    public record PlayerData(String playerId, String nickname) {
    }
}
