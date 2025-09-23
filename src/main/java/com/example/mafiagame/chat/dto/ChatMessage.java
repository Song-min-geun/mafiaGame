package com.example.mafiagame.chat.dto;

import java.util.Map;

import lombok.*;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    
    public enum MessageType {
        CREATED,
        CHAT, 
        USER_JOINED,    // 사용자 입장 (구조화된 데이터 포함)
        USER_LEFT,      // 사용자 퇴장 (구조화된 데이터 포함)
        HOST_CHANGED,   // 방장 변경 (구조화된 데이터 포함)
        GAME_START, 
        GAME_END, 
        PHASE_CHANGE, 
        VOTE, 
        ROLE_ASSIGN, 
        GAME_RESULT
    }
    
    private MessageType type;
    // Getter 메서드들
    private String roomId;
    private String senderId;
    private String senderName;
    private String content;
    private Long timestamp;

    private Map<String, Object> data;
}
