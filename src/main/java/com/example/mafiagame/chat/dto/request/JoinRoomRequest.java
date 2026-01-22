package com.example.mafiagame.chat.dto.request;

import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.user.domain.User;

public record JoinRoomRequest(
        String roomId,
        String userId) {

    public ChatUser toParticipant(User user) {
        return ChatUser.builder()
                .userId(user.getUserLoginId())
                .userName(user.getNickname())
                .isHost(false)
                .build();
    }
}
