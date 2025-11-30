package com.example.mafiagame.game.dto.request;

public record PoliceResultMessage(
        String type,
        String senderId,
        String senderName,
        String roomId,
        String policeId,
        String content,
        String timestamp
) {
}
