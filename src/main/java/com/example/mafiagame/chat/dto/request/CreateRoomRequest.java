package com.example.mafiagame.chat.dto.request;

import java.util.UUID;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.user.domain.Users;

public record CreateRoomRequest(
        String roomName) {

    public ChatRoom toEntity(Users host) {
        String generatedRoomName = (roomName == null || roomName.trim().isEmpty())
                ? "마피아 게임 #" + (int) (Math.random() * 1000)
                : roomName;

        return ChatRoom.builder()
                .roomId(UUID.randomUUID().toString())
                .roomName(generatedRoomName)
                .hostId(host.getUserLoginId())
                .hostName(host.getNickname())
                .build();
    }

    public ChatUser toHostParticipant(Users host) {
        return ChatUser.builder()
                .userId(host.getUserLoginId())
                .userName(host.getNickname())
                .isHost(true)
                .build();
    }
}
