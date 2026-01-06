package com.example.mafiagame.user.dto.reponse;

public record TokenResponse(
        String accessToken,
        String refreshToken) {
}
