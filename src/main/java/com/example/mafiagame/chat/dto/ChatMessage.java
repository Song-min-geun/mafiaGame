package com.example.mafiagame.chat.dto;

import java.util.Map;

import com.example.mafiagame.chat.domain.ChatRoom;

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

    private MessageType type;
    private String roomId;
    private String roomName;
    private String senderId;
    private String senderName;
    private String recipient;
    private String content;
    private Long timestamp;
    private Map<String, Object> data;

    // ================== Factory Methods ================== //

    /**
     * 시스템 메시지 생성
     */
    public static ChatMessage system(String roomId, String content) {
        return ChatMessage.builder()
                .type(MessageType.SYSTEM)
                .roomId(roomId)
                .senderId("SYSTEM")
                .senderName("시스템")
                .content(content)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 사용자 입장 메시지 생성
     */
    public static ChatMessage userJoined(ChatRoom room, String content) {
        return ChatMessage.builder()
                .type(MessageType.USER_JOINED)
                .roomId(room.getRoomId())
                .roomName(room.getRoomName())
                .content(content)
                .data(Map.of("room", room))
                .timestamp(System.currentTimeMillis())
                .build();
    }

    /**
     * 사용자 퇴장 메시지 생성
     */
    public static ChatMessage userLeft(ChatRoom room, String userName) {
        return ChatMessage.builder()
                .type(MessageType.USER_LEFT)
                .roomId(room.getRoomId())
                .senderId("SYSTEM")
                .senderName("시스템")
                .content(userName + "님이 나갔습니다.")
                .data(Map.of("room", room))
                .timestamp(System.currentTimeMillis())
                .build();
    }
}