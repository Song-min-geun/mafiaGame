package com.example.mafiagame.chat.dto.request;

import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.user.domain.Users;

public record JoinRoomRequest(
        String roomId,
        String userId) {

    public ChatUser toParticipant(Users user) {
        return ChatUser.builder()
                .userId(user.getUserLoginId())
                .userName(user.getNickname())
                .isHost(false)
                .build();
    }
}
