package com.example.mafiagame.chat.controller;

import java.security.Principal;
import java.util.Map;

import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;

import com.example.mafiagame.chat.domain.ChatRoom;
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

    @MessageMapping("/chat.sendMessage")
    public void sendMessage(@Payload ChatMessage chatMessage, SimpMessageHeaderAccessor accessor) {

        Principal principal = null;
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("user") instanceof Principal) {
            principal = (Principal) sessionAttributes.get("user");
        }

        if (principal == null) {
            log.error("❌❌❌ 최종 실패: 세션에서 Principal을 찾을 수 없습니다! ❌❌❌");
            return;
        }

        String senderLoginId = principal.getName();
        // 보안을 위해 발신자 ID와 이름을 서버에서 다시 설정
        chatMessage.setSenderId(senderLoginId);
        String senderName = chatRoomService.getParticipantName(chatMessage.getRoomId(), senderLoginId);
        chatMessage.setSenderName(senderName);

        log.info("메시지 방송 시작 - 방: {}, 발신자: {}", chatMessage.getRoomId(), senderLoginId);

        // ❗ 핵심: 이제 메시지를 해당 방의 공용 토픽으로 방송합니다.
        messagingTemplate.convertAndSend("/topic/room." + chatMessage.getRoomId(), chatMessage);
    }

    @MessageMapping("/room.join")
    public void joinRoom(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor accessor) {
        // ❗ 수정: sessionAttributes에서 사용자 정보 가져오기
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null || sessionAttributes.get("user") == null) {
            log.error("❌❌❌ 방 입장 실패: 세션에 사용자 정보가 없습니다! ❌❌❌");
            return;
        }
        
        UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) sessionAttributes.get("user");
        String senderLoginId = auth.getName();
        
        String roomId = (String) payload.get("roomId");
        String senderName = chatRoomService.getParticipantName(roomId, senderLoginId);

        log.info("senderLoginId: {}, senderName: {}", senderLoginId, senderName);
        log.info("User {} joining room: {}", senderName, roomId);

        // ❗ 수정: 구조화된 데이터와 함께 메시지 전송
        ChatRoom room = chatRoomService.getRoom(roomId);
        if (room != null) {
            Map<String, Object> roomData = Map.of(
                "participants", room.getParticipants(),
                "participantCount", room.getParticipants().size(),
                "hostId", room.getHostId(),
                "maxPlayers", room.getMaxPlayers()
            );

            // ❗ 추가: 방장인지 확인하여 메시지 내용 구분
            boolean isHost = room.getHostId().equals(senderLoginId);
            String messageContent = isHost ? 
                senderName + "님이 방을 생성하였습니다." : 
                senderName + "님이 입장하였습니다.";

            ChatMessage joinMessage = ChatMessage.builder()
                    .type(ChatMessage.MessageType.USER_JOINED)
                    .roomId(roomId)
                    .senderId("SYSTEM")
                    .senderName("시스템")
                    .content(messageContent)
                    .timestamp(System.currentTimeMillis())
                    .data(roomData)
                    .build();

            log.info("🔔 시스템 메시지 전송: {}", joinMessage);
            log.info("🔔 메시지 타입: {}", joinMessage.getType());
            log.info("🔔 발신자 ID: {}", joinMessage.getSenderId());
            log.info("🔔 메시지 내용: {}", joinMessage.getContent());
            log.info("🔔 전송 대상: /topic/room.{}", roomId);
            
            messagingTemplate.convertAndSend("/topic/room." + roomId, joinMessage);
        }
    }

    @MessageMapping("/room.leave")
    public void leaveRoom(@Payload Map<String, Object> payload, SimpMessageHeaderAccessor accessor) {
        // ❗ 수정: sessionAttributes에서 사용자 정보 가져오기
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null || sessionAttributes.get("user") == null) {
            log.error("❌❌❌ 방 나가기 실패: 세션에 사용자 정보가 없습니다! ❌❌❌");
            return;
        }
        
        UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) sessionAttributes.get("user");
        String senderLoginId = auth.getName();

        String roomId = (String) payload.get("roomId");
        String senderName = chatRoomService.getParticipantName(roomId, senderLoginId);

        log.info("User {} leaving room: {}", senderName, roomId);

        // 방 나가기 처리 (방장 변경 포함)
        boolean hostChanged = chatRoomService.leaveRoom(roomId, senderLoginId);
        
        // ❗ 수정: 구조화된 데이터와 함께 메시지 전송
        ChatRoom room = chatRoomService.getRoom(roomId);
        if (room != null) {
            Map<String, Object> roomData = Map.of(
                "participants", room.getParticipants(),
                "participantCount", room.getParticipants().size(),
                "hostId", room.getHostId(),
                "maxPlayers", room.getMaxPlayers(),
                "hostChanged", hostChanged
            );

            ChatMessage leaveMessage = ChatMessage.builder()
                    .type(ChatMessage.MessageType.USER_LEFT)
                    .roomId(roomId)
                    .senderId("SYSTEM")
                    .senderName("시스템")
                    .content(senderName + "님이 나갔습니다.")
                    .timestamp(System.currentTimeMillis())
                    .data(roomData)
                    .build();

            messagingTemplate.convertAndSend("/topic/room." + roomId, leaveMessage);
        }
    }

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