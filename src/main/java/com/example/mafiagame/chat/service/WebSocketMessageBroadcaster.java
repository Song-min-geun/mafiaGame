package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketMessageBroadcaster {

    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationContext applicationContext;

    /**
     * 특정 방의 모든 사용자에게 메시지 전송
     */
    public void broadcastToRoom(String roomId, Object message) {
        messagingTemplate.convertAndSend("/topic/room." + roomId, message);
    }

    /**
     * 방 목록 갱신 신호 전송
     */
    public void notifyRoomListUpdated() {
        messagingTemplate.convertAndSend("/topic/rooms", Map.of("type", "ROOM_LIST_UPDATED"));
    }

    /**
     * 시스템 메시지 전송 (방 전체)
     */
    public void sendSystemMessage(String roomId, String content) {
        ChatMessage message = ChatMessage.system(roomId, content);
        broadcastToRoom(roomId, message);
    }

    // ================== 개인 메시지 ================== //

    /**
     * 특정 사용자에게 메시지 전송
     */
    public void sendToUser(String userId, Object message) {
        if (!isUserConnected(userId)) {
            log.warn("메시지 수신자가 WebSocket에 등록되지 않음: {}", userId);
            return;
        }

        try {
            messagingTemplate.convertAndSendToUser(userId, "/queue/private", message);
        } catch (Exception e) {
            log.error("개인 메시지 전송 실패: userId={}, error: {}", userId, e.getMessage());
        }
    }

    /**
     * 에러 메시지 전송
     */
    public void sendError(String userId, String errorMessage) {
        Map<String, Object> message = Map.of(
                "type", "ERROR",
                "content", errorMessage);
        sendToUser(userId, message);
    }

    /**
     * 채팅 메시지 전송 (개인)
     */
    public void sendPrivateMessage(String userId, ChatMessage message) {
        sendToUser(userId, message);
    }

    // ================== 게임 관련 메시지 ================== //

    /**
     * 게임 시작 메시지
     */
    public void sendGameStart(String roomId, Object gameState) {
        broadcastToRoom(roomId, Map.of("type", "GAME_START", "game", gameState));
    }

    /**
     * 게임 종료 메시지
     */
    public void sendGameEnded(String roomId, String winner, Object players) {
        broadcastToRoom(roomId, Map.of("type", "GAME_ENDED", "winner", winner, "players", players));
    }

    /**
     * 페이즈 변경 메시지
     */
    public void sendPhaseChange(String roomId, Object gameState) {
        broadcastToRoom(roomId, Map.of("type", "PHASE_CHANGE", "gameState", gameState));
    }

    /**
     * 타이머 업데이트 메시지
     */
    public void sendTimerUpdate(String roomId, Long phaseEndTime) {
        broadcastToRoom(roomId, Map.of("type", "TIMER_UPDATE", "phaseEndTime", phaseEndTime));
    }

    // ================== 헬퍼 메소드 ================== //

    private boolean isUserConnected(String userId) {
        SimpUserRegistry userRegistry = getSimpUserRegistry();
        if (userRegistry == null) {
            return true; // Registry 없으면 일단 전송 시도
        }
        return userRegistry.getUser(userId) != null;
    }

    private SimpUserRegistry getSimpUserRegistry() {
        try {
            return applicationContext.getBean(SimpUserRegistry.class);
        } catch (Exception e) {
            log.warn("SimpUserRegistry를 가져올 수 없습니다: {}", e.getMessage());
            return null;
        }
    }
}
