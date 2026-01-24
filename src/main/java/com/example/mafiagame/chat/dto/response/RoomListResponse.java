package com.example.mafiagame.chat.dto.response;

import com.example.mafiagame.chat.domain.ChatRoom;

public record RoomListResponse(
        String roomId,
        String roomName,
        int participantsCount,
        int maxPlayers,
        String hostName,
        boolean isPlaying) {

    public static RoomListResponse from(ChatRoom chatRoom) {
        return new RoomListResponse(
                chatRoom.getRoomId(),
                chatRoom.getRoomName(),
                chatRoom.getParticipants().size(),
                chatRoom.getMaxPlayers(),
                chatRoom.getHostName(),
                chatRoom.isPlaying());
    }
}
