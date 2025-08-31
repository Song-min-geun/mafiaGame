package com.example.mafiagame.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    
    public enum MessageType {
        CHAT, JOIN, LEAVE, GAME_START, GAME_END, PHASE_CHANGE, VOTE, ROLE_ASSIGN, GAME_RESULT
    }
    
    private MessageType type;
    private String roomId;
    private String senderId;
    private String senderName;
    private String content;
    private Long timestamp;
}
