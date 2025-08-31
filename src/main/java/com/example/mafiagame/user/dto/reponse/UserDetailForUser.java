package com.example.mafiagame.user.dto.reponse;

public record UserDetailForUser(
        String nickname
        // 유저 레벨, 승률, 해당 방 점수
) {
    public UserDetailForUser(String nickname) {
        this.nickname = nickname;
    }
}
