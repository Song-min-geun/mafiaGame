package com.example.mafiagame.chat.dto.request;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.user.domain.User;

public record CreateRoomRequest(
        String roomName,
        String hostId) {
    public ChatRoom toEntity(User host) {
        return ChatRoom.builder()
                .roomName(roomName)
                .hostId(host.getUserLoginId())
                .hostName(host.getNickname())
                .build();
    }

    public ChatUser toHostParticipant(User host) {
        return ChatUser.builder()
                .userId(host.getUserLoginId())
                .userName(host.getNickname())
                .isHost(true)
                .build();
    }
}
