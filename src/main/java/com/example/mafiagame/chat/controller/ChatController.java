package com.example.mafiagame.chat.controller;

import java.security.Principal;
import java.util.Map;

import com.example.mafiagame.chat.domain.ChatUser;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.service.ChatRoomService;
import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.game.service.GameTimerService;
import com.example.mafiagame.global.service.RedisService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Controller
@RequiredArgsConstructor
@Slf4j
public class ChatController {

    private final SimpMessagingTemplate messagingTemplate;
    private final ChatRoomService chatRoomService;
    private final GameService gameService;
    private final GameTimerService gameTimerService;
    private final RedisService redisService;

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

        // 게임 상태에 따른 채팅 제한 확인
        if (!canPlayerChat(chatMessage.getRoomId(), senderLoginId)) {
            log.warn("플레이어의 채팅 시도 차단: {}", senderLoginId);
            return;
        }
        
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
        
        // WebSocket 연결 상태 등록
        chatRoomService.registerWebSocketConnection(senderLoginId);
        
        // 사용자 세션 저장 (방 정보)
        redisService.saveUserSession(senderLoginId, roomId, null);

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
        String gameId = (String) payload.get("gameId");

        log.info("Starting game in room: {} with gameId: {}", roomId, gameId);

        // 방의 모든 참가자에게 게임 세션 저장
        ChatRoom room = chatRoomService.getRoom(roomId);
        if (room != null && room.getParticipants() != null) {
            for (ChatUser participant : room.getParticipants()) {
                redisService.saveUserSession(participant.getUserId(), roomId, gameId);
            }
        }

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
    
    /**
     * WebSocket 연결 해제 이벤트 처리
     */
    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        
        if (sessionAttributes != null && sessionAttributes.get("user") != null) {
            UsernamePasswordAuthenticationToken auth = (UsernamePasswordAuthenticationToken) sessionAttributes.get("user");
            String userId = auth.getName();
            
            // WebSocket 연결 상태 해제
            chatRoomService.unregisterWebSocketConnection(userId);
            log.info("WebSocket 연결 해제됨: {}", userId);
        }
    }
    
    /**
     * 플레이어가 채팅할 수 있는지 확인
     */
    private boolean canPlayerChat(String roomId, String playerId) {
        try {
            // 게임이 진행 중인지 확인
            Game game = gameService.getGameByRoomId(roomId);
            if (game == null) {
                log.info("🔍 채팅 권한 확인: 게임이 없음 - 채팅 허용. roomId={}, playerId={}", roomId, playerId);
                return true; // 게임이 없으면 채팅 허용
            }
            
            log.info("🔍 채팅 권한 확인: 게임 존재. gameId={}, gamePhase={}, playerId={}", 
                    game.getGameId(), game.getGamePhase(), playerId);
            
            // 죽은 플레이어는 채팅 불가
            if (gameService.isPlayerInDeadChatRoom(roomId, playerId)) {
                log.info("🔍 채팅 권한 확인: 죽은 플레이어 - 채팅 차단. playerId={}", playerId);
                return false;
            }
            
            // 최종 변론 페이즈에서는 투표 결과 플레이어만 채팅 가능
            if (game.getGamePhase() != null && game.getGamePhase().name().equals("DAY_FINAL_DEFENSE")) {
                String votedPlayerId = gameService.getVotedPlayerId(game.getGameId());
                log.info("🔍 채팅 권한 확인: 최종 변론 페이즈. votedPlayerId={}, currentPlayerId={}", 
                        votedPlayerId, playerId);
                
                if (votedPlayerId != null && !votedPlayerId.equals(playerId)) {
                    log.info("🚫 최종 변론 페이즈: 투표 결과 플레이어만 채팅 가능. 현재 플레이어: {}, 투표 결과 플레이어: {}", 
                            playerId, votedPlayerId);
                    return false;
                } else if (votedPlayerId != null && votedPlayerId.equals(playerId)) {
                    log.info("✅ 최종 변론 페이즈: 최다 득표자 - 채팅 허용. playerId={}", playerId);
                    return true;
                } else {
                    log.warn("⚠️ 최종 변론 페이즈: votedPlayerId가 null. playerId={}", playerId);
                    return false; // votedPlayerId가 null이면 채팅 차단
                }
            }
            
            log.info("🔍 채팅 권한 확인: 일반 페이즈 - 채팅 허용. gamePhase={}, playerId={}", 
                    game.getGamePhase(), playerId);
            return true;
        } catch (Exception e) {
            log.error("❌ 플레이어 채팅 권한 확인 중 오류 발생: {}", e.getMessage(), e);
            return true; // 오류 시 채팅 허용
        }
    }
    
    /**
     * 플레이어가 죽었는지 확인 (기존 메서드 유지)
     */
    private boolean isPlayerDead(String roomId, String playerId) {
        try {
            // 게임이 진행 중인지 확인
            Game game = gameService.getGameByRoomId(roomId);
            if (game == null) {
                return false; // 게임이 없으면 채팅 허용
            }
            
            // 플레이어가 죽은 플레이어 채팅방에 있는지 확인
            return gameTimerService.isPlayerInDeadChatRoom(roomId, playerId);
        } catch (Exception e) {
            log.error("플레이어 생존 상태 확인 실패: roomId={}, playerId={}", roomId, playerId, e);
            return false; // 오류 시 채팅 허용
        }
    }
}