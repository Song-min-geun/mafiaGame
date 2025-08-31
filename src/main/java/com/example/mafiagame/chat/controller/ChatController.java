package com.example.mafiagame.chat.controller;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.service.ChatRoomService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRoomService chatRoomService;

    // 채팅 메시지 전송
    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, Principal principal) {
        log.info("=== CHAT MESSAGE RECEIVED ===");
        log.info("ChatMessage: {}", chatMessage);
        log.info("Principal: {}", principal);
        log.info("Principal type: {}", principal != null ? principal.getClass().getName() : "NULL");
        
        // ❗ 1. principal이 null이면, 인증되지 않은 접근이므로 즉시 중단합니다.
        if (principal == null) {
            log.error("❌❌❌ Principal is NULL! WebSocket 인증 실패! ❌❌❌");
            log.error("메시지 전송자: {}", chatMessage.getSenderId());
            log.error("메시지 내용: {}", chatMessage.getContent());
            log.error("방 ID: {}", chatMessage.getRoomId());
            log.error("Cannot send message without authenticated user.");
            return;
        }
        
        // ❗ 2. 반드시 Principal에서 사용자 ID를 가져와 사용합니다. 이것이 "신뢰할 수 있는" 발신자 ID입니다.
        String senderLoginId = principal.getName();
        log.info("Principal name: {}", senderLoginId);
        log.info("Principal toString: {}", principal.toString());
        
        // ❗ 3. 클라이언트가 보낸 senderId와 실제 인증된 사용자가 다른 경우를 방지
        if (chatMessage.getSenderId() != null && !chatMessage.getSenderId().equals(senderLoginId)) {
            log.warn("클라이언트 senderId ({})와 Principal name ({})이 일치하지 않음. Principal을 신뢰합니다.", 
                chatMessage.getSenderId(), senderLoginId);
        }
        
        // ❗ 4. ChatMessage의 senderId를 Principal의 값으로 덮어씁니다 (보안 강화)
        chatMessage.setSenderId(senderLoginId);
        
        log.info("Processing message for room: {} with authenticated sender: {}", chatMessage.getRoomId(), senderLoginId);
        
        // ❗ 5. 신뢰할 수 있는 senderLoginId를 메시지 처리 로직에 전달합니다.
        processMessage(chatMessage, senderLoginId);
    }
    
    // 메시지 처리 로직 분리
    private void processMessage(ChatMessage chatMessage, String senderId) {
        log.info("메시지 처리 시작 - 방: {}, 인증된 발신자: {}", chatMessage.getRoomId(), senderId);
        
        // 특정 방으로 메시지 전송 (발신자 제외)
        if (chatMessage.getRoomId() != null) {
            // 방의 모든 참여자에게 발신자 제외하고 전송
            List<String> participants = chatRoomService.getRoomParticipants(chatMessage.getRoomId());
            log.info("방 참가자 목록: {} (총 {}명)", participants, participants.size());
            
            if (participants.isEmpty()) {
                // 백업: 기존 방식으로 전송
                log.info("방에 참가자가 없음. 전역 토픽으로 메시지 전송: /topic/room.{}", chatMessage.getRoomId());
                messagingTemplate.convertAndSend("/topic/room." + chatMessage.getRoomId(), chatMessage);
            } else {
                int sentCount = 0;
                for (String participantId : participants) {
                    if (!participantId.equals(senderId)) {
                        // 개인 큐로 메시지 전송 (올바른 형식)
                        String destination = "/queue/room." + chatMessage.getRoomId();
                        log.info("개인 메시지 전송: {} -> {} (destination: {})", senderId, participantId, destination);
                        messagingTemplate.convertAndSendToUser(participantId, destination, chatMessage);
                        sentCount++;
                    } else {
                        log.info("발신자 본인 제외: {} (자기 자신에게는 메시지 전송하지 않음)", participantId);
                    }
                }
                log.info("메시지 전송 완료: {}명에게 전송됨", sentCount);
            }
        } else {
            log.warn("Message has no roomId: {}", chatMessage);
        }
    }

    // 방 입장
    @MessageMapping("/room.join")
    public void joinRoom(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor headerAccessor) {
        String roomId = (String) payload.get("roomId");
        String userId = (String) payload.get("userId");
        String userName = (String) payload.get("userName");
        
        log.info("User {} joining room: {}", userName, roomId);
        
        // 방 입장 성공 시 방의 모든 사용자에게 알림
        ChatMessage joinMessage = ChatMessage.builder()
                .type(ChatMessage.MessageType.JOIN)
                .roomId(roomId)
                .senderId(userId)
                .senderName(userName)
                .content(userName + "님이 방에 입장했습니다.")
                .timestamp(System.currentTimeMillis())
                .build();
        
        messagingTemplate.convertAndSend("/topic/room." + roomId, joinMessage);
    }

    // 방 나가기
    @MessageMapping("/room.leave")
    public void leaveRoom(@Payload Map<String, Object> payload) {
        String roomId = (String) payload.get("roomId");
        String userId = (String) payload.get("userId");
        String userName = (String) payload.get("userName");
        
        log.info("User {} leaving room: {}", userName, roomId);
        
        // 방 나가기 시 방의 모든 사용자에게 알림
        ChatMessage leaveMessage = ChatMessage.builder()
                .type(ChatMessage.MessageType.LEAVE)
                .roomId(roomId)
                .senderId(userId)
                .senderName(userName)
                .content(userName + "님이 방을 나갔습니다.")
                .timestamp(System.currentTimeMillis())
                .build();
        
        messagingTemplate.convertAndSend("/topic/room." + roomId, leaveMessage);
    }

    // 게임 시작
    @MessageMapping("/game.start")
    public void startGame(@Payload Map<String, Object> payload) {
        String roomId = (String) payload.get("roomId");
        
        log.info("Starting game in room: {}", roomId);
        
        ChatMessage gameStartMessage = ChatMessage.builder()
                .type(ChatMessage.MessageType.GAME_START)
                .roomId(roomId)
                .senderName("시스템")
                .content("게임이 시작되었습니다!")
                .timestamp(System.currentTimeMillis())
                .build();
        
        messagingTemplate.convertAndSend("/topic/room." + roomId, gameStartMessage);
    }

    // 게임 종료
    @MessageMapping("/game.end")
    public void endGame(@Payload Map<String, Object> payload) {
        String roomId = (String) payload.get("roomId");
        
        log.info("Ending game in room: {}", roomId);
        
        ChatMessage gameEndMessage = ChatMessage.builder()
                .type(ChatMessage.MessageType.GAME_END)
                .roomId(roomId)
                .senderName("시스템")
                .content("게임이 종료되었습니다.")
                .timestamp(System.currentTimeMillis())
                .build();
        
        messagingTemplate.convertAndSend("/topic/room." + roomId, gameEndMessage);
    }
}
