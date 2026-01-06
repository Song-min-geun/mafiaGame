package com.example.mafiagame.chat.dto;

import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {

    public enum MessageType {
        ROOM_CREATED,
        CHAT,
        USER_JOINED,
        USER_LEFT,
        HOST_CHANGED,
        GAME_START,
        GAME_END,
        PHASE_CHANGE,
        VOTE,
        ROLE_ASSIGN,
        GAME_RESULT
    }

    private MessageType type;
    private String roomId;
    private String roomName;
    private String senderId;
    private String senderName;
    private String recipient;
    private String content;
    private Long timestamp;

    private Map<String, Object> data;
}