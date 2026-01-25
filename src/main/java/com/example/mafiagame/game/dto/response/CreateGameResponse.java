package com.example.mafiagame.game.dto.response;

public record CreateGameResponse(
        boolean success,
        String gameId,
        String roomId,
        String message) {
    public static CreateGameResponse success(String gameId, String roomId) {
        return new CreateGameResponse(true, gameId, roomId, "게임 생성 성공");
    }

    public static CreateGameResponse fail(String message) {
        return new CreateGameResponse(false, null, null, message);
    }
}
