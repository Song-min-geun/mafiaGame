package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.dto.request.JoinRoomRequest;
import com.example.mafiagame.chat.dto.request.LeaveRoomRequest;
import com.example.mafiagame.chat.service.ChatRoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatRoomControllerTest {

    @Mock
    private ChatRoomService chatRoomService;

    @Mock
    private SimpMessageHeaderAccessor headerAccessor;

    @Mock
    private Principal principal;

    @InjectMocks
    private ChatRoomController chatRoomController;

    private Map<String, Object> sessionAttributes;

    @BeforeEach
    void setUp() {
        sessionAttributes = new HashMap<>();
        sessionAttributes.put("user", principal);
    }

    @Test
    @DisplayName("메시지 전송 - 성공")
    void sendMessage_success() {
        // given
        when(principal.getName()).thenReturn("testUser");
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .content("안녕하세요")
                .build();
        when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);

        // when
        chatRoomController.sendMessage(message, headerAccessor);

        // then
        verify(chatRoomService).processAndBroadcastMessage(message, "testUser");
    }

    @Test
    @DisplayName("메시지 전송 - Principal 없음")
    void sendMessage_noPrincipal() {
        // given
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .content("안녕하세요")
                .build();
        when(headerAccessor.getSessionAttributes()).thenReturn(null);

        // when
        chatRoomController.sendMessage(message, headerAccessor);

        // then
        verify(chatRoomService, never()).processAndBroadcastMessage(any(), anyString());
    }

    @Test
    @DisplayName("개인 메시지 전송 - 성공")
    void sendPrivateMessage_success() {
        // given
        when(principal.getName()).thenReturn("testUser");
        ChatMessage message = ChatMessage.builder()
                .roomId("room-123")
                .recipient("otherUser")
                .content("비밀 대화")
                .build();
        when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);

        // when
        chatRoomController.sendPrivateMessage(message, headerAccessor);

        // then
        verify(chatRoomService).processAndPrivateMessage(message, "testUser");
    }

    @Test
    @DisplayName("채팅방 참여 - 성공")
    void joinRoom_success() {
        // given
        when(principal.getName()).thenReturn("testUser");
        Map<String, Object> payload = Map.of("roomId", "room-123");
        when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);

        // when
        chatRoomController.joinRoom(payload, headerAccessor);

        // then
        ArgumentCaptor<JoinRoomRequest> requestCaptor = ArgumentCaptor.forClass(JoinRoomRequest.class);
        verify(chatRoomService).userJoin(requestCaptor.capture());
        JoinRoomRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.roomId()).isEqualTo("room-123");
        assertThat(capturedRequest.userId()).isEqualTo("testUser");
    }

    @Test
    @DisplayName("채팅방 탈퇴 - 성공")
    void leaveRoom_success() {
        // given
        when(principal.getName()).thenReturn("testUser");
        Map<String, Object> payload = Map.of("roomId", "room-123");
        when(headerAccessor.getSessionAttributes()).thenReturn(sessionAttributes);

        // when
        chatRoomController.leaveRoom(payload, headerAccessor);

        // then
        ArgumentCaptor<LeaveRoomRequest> requestCaptor = ArgumentCaptor.forClass(LeaveRoomRequest.class);
        verify(chatRoomService).userLeave(requestCaptor.capture());
        LeaveRoomRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.roomId()).isEqualTo("room-123");
        assertThat(capturedRequest.userId()).isEqualTo("testUser");
    }

    @Test
    @DisplayName("WebSocket 연결 끊김 이벤트 처리 - 성공")
    void handleWebSocketDisconnectListener_success() {
        // given
        when(principal.getName()).thenReturn("testUser");
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
                .setHeader(SimpMessageHeaderAccessor.SESSION_ATTRIBUTES, sessionAttributes)
                .build();

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getMessage()).thenReturn(message);

        // when
        chatRoomController.handleWebSocketDisconnectListener(event);

        // then
        verify(chatRoomService).handleDisconnect("testUser");
    }
}
