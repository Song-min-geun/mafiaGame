package com.example.mafiagame.chat.dto.request;

public record LeaveRoomRequest(
                String roomId,
                String userId) {

        public static LeaveRoomRequest of(String roomId, String userId) {
                return new LeaveRoomRequest(roomId, userId);
        }
}
