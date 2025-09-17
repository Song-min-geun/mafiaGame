package com.example.mafiagame.game.dto.request;


public record NightResultMessageDto(
        String type,
        String senderId,
        String senderName,
        String roomId,
        String content,
        String timestamp,
        String killedPlayerId
) {
}
