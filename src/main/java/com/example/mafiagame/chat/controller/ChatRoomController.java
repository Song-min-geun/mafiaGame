package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.util.Map;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    private Principal getPrincipal(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("user") instanceof Principal) {
            return (Principal) sessionAttributes.get("user");
        }
        return null;
    }

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null) {
            log.error("sendMessage 실패: Principal 객체를 찾을 수 없습니다.");
            return;
        }
        chatRoomService.processAndBroadcastMessage(chatMessage, principal.getName());
    }

    @MessageMapping("/chat.sendPrivateMessage")
    public void sendPrivateMessage(@Payload ChatMessage chatMessage, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null) {
            log.error("sendPrivateMessage 실패: Principal 객체를 찾을 수 없습니다.");
            return;
        }
        chatRoomService.processAndPrivateMessage(chatMessage, principal.getName());
    }

    @MessageMapping("/room.join")
    public void joinRoom(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null) {
            log.error("joinRoom 실패: Principal 객체를 찾을 수 없습니다.");
            return;
        }
        String roomId = (String) payload.get("roomId");
        chatRoomService.userJoin(roomId, principal.getName());
    }

    @MessageMapping("/room.leave")
    public void leaveRoom(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null) {
            log.error("leaveRoom 실패: Principal 객체를 찾을 수 없습니다.");
            return;
        }
        String roomId = (String) payload.get("roomId");
        chatRoomService.userLeave(roomId, principal.getName());
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("user") instanceof Principal) {
            Principal principal = (Principal) sessionAttributes.get("user");
            String userId = principal.getName();
            // log.info("WebSocket 연결 해제됨: {}", userId);
            chatRoomService.handleDisconnect(userId);
        }
    }
}
