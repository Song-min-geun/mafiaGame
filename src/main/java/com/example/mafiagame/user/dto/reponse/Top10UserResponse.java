package com.example.mafiagame.user.dto.reponse;

public record Top10UserResponse(
        String nickname,
        Double winRate,
        int playCount) {
}
