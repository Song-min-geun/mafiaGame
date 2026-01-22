package com.example.mafiagame.chat.dto.response;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;

import java.util.List;

public record RoomResponse(
        String roomId,
        String roomName,
        String hostId,
        String hostName,
        List<ParticipantInfo> participants,
        int maxPlayers,
        boolean isPlaying) {
    public static RoomResponse from(ChatRoom room) {
        return new RoomResponse(
                room.getRoomId(),
                room.getRoomName(),
                room.getHostId(),
                room.getHostName(),
                room.getParticipants().stream()
                        .map(ParticipantInfo::from)
                        .toList(),
                room.getMaxPlayers(),
                room.isPlaying());
    }

    public record ParticipantInfo(String userId, String userName, boolean isHost) {
        public static ParticipantInfo from(ChatUser user) {
            return new ParticipantInfo(user.getUserId(), user.getUserName(), user.isHost());
        }
    }
}
