package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.domain.GameStatus;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    private final Map<String, ChatRoom> chatRooms = new ConcurrentHashMap<>();
    private final UserService userService;
    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ApplicationContext applicationContext;

    // ================== 메시지 처리 (핵심 로직) ================== //

    //
    public void processAndBroadcastMessage(ChatMessage chatMessage, String senderId) {
        User sender = userService.getUserByLoginId(senderId);

        // 보안을 위해 발신자 정보 서버에서 설정
        chatMessage.setSenderId(sender.getUserLoginId());
        chatMessage.setSenderName(sender.getNickname());

        // 채팅 권한 검사
        if (!canPlayerChat(chatMessage.getRoomId(), senderId)) {
            sendErrorMessageToUser(senderId, "지금은 채팅을 할 수 없습니다.");
            return;
        }

        messagingTemplate.convertAndSend("/topic/room." + chatMessage.getRoomId(), chatMessage);
    }

    public void processAndPrivateMessage(ChatMessage chatMessage, String senderId) {
        String recipientId = chatMessage.getRecipient();
        if (recipientId == null || recipientId.trim().isEmpty()) {
            sendErrorMessageToUser(senderId, "메세지를 보낼 대상을 지정해주세요.");
            return;
        }

        User sender = userService.getUserByLoginId(senderId);
        chatMessage.setSenderId(sender.getUserLoginId());
        chatMessage.setSenderName(sender.getNickname());

        // recipientId는 로그인 아이디(Principal 이름)여야 함
        log.info("개인 메시지 전송 시도: senderId={}, recipientId={}, destination=/user/{}/queue/private",
                senderId, recipientId, recipientId);

        // 사용자 등록 상태 확인
        SimpUserRegistry userRegistry = getSimpUserRegistry();
        if (userRegistry != null) {
            var recipientUser = userRegistry.getUser(recipientId);
            if (recipientUser == null) {
                log.warn("수신자가 WebSocket에 등록되지 않음: {}", recipientId);
                log.info("현재 등록된 사용자들: {}", userRegistry.getUsers());
                sendErrorMessageToUser(senderId, "수신자가 온라인이 아닙니다.");
                return;
            }

            log.info("수신자 등록 확인됨: {} (세션 수: {})", recipientId, recipientUser.getSessions().size());
        } else {
            log.warn("SimpUserRegistry를 사용할 수 없어 사용자 등록 상태를 확인할 수 없습니다.");
        }

        try {
            messagingTemplate.convertAndSendToUser(recipientId, "/queue/private", chatMessage);
            log.info("개인 메시지 전송 성공: from {} to {}", senderId, recipientId);
        } catch (Exception e) {
            log.error("개인 메시지 전송 실패: from {} to {}, error: {}", senderId, recipientId, e.getMessage());
            sendErrorMessageToUser(senderId, "메시지 전송에 실패했습니다.");
        }
    }

    // ================== 채팅방 및 사용자 관리 ================== //

    public ChatRoom createRoom(String roomName, String hostId) {
        User host = userService.getUserByLoginId(hostId);
        ChatRoom room = new ChatRoom(roomName, host.getUserLoginId(), host.getNickname());
        room.addParticipant(host.getUserLoginId(), host.getNickname(), true);
        chatRooms.put(room.getRoomId(), room);
        log.info("채팅방 생성됨: {} (호스트: {})", room.getRoomId(), host.getNickname());
        return room;
    }

    public void userJoin(String roomId, String userId) {
        ChatRoom room = getRoom(roomId);
        if (room == null) {
            sendErrorMessageToUser(userId, "채팅방이 존재하지 않습니다.");
            return;
        }

        User user = userService.getUserByLoginId(userId);
        room.addParticipant(user.getUserLoginId(), user.getNickname(), false);

        String content;
        if (room.getHostId().equals(user.getUserLoginId())) {
            content = user.getNickname() + "님이 방을 개설하였습니다.";
        } else {
            content = user.getNickname() + "님이 입장하였습니다.";
        }

        ChatMessage joinMessage = ChatMessage.builder()
                .type(ChatMessage.MessageType.USER_JOINED)
                .roomId(roomId)
                .roomName(room.getRoomName())
                .content(content)
                .data(Map.of("room", room)) // 참가자 목록 대신 방 전체 정보 전송
                .build();

        messagingTemplate.convertAndSend("/topic/room." + roomId, joinMessage);
        // 모든 클라이언트에게 방 목록 갱신 신호 전송
        messagingTemplate.convertAndSend("/topic/rooms", Map.of("type", "ROOM_LIST_UPDATED"));
    }

    @Transactional
    public void userLeave(String roomId, String userId) {
        ChatRoom room = getRoom(roomId);
        if (room == null)
            return;

        String leftUserName = room.removeParticipant(userId);
        if (leftUserName == null)
            return; // 방에 없는 유저가 나가는 경우

        if (room.getParticipants().size() > 1) {
            ChatMessage leaveMessage = ChatMessage.builder()
                    .type(ChatMessage.MessageType.USER_LEFT)
                    .roomId(roomId)
                    .senderId("SYSTEM")
                    .senderName("시스템")
                    .content(leftUserName + "님이 나갔습니다.")
                    .data(Map.of("room", room)) // 참가자 목록 대신 방 전체 정보 전송
                    .build();
            messagingTemplate.convertAndSend("/topic/room." + roomId, leaveMessage);
        } else {
            deleteRoom(roomId);
        }
        // 모든 클라이언트에게 방 목록 갱신 신호 전송
        messagingTemplate.convertAndSend("/topic/rooms", Map.of("type", "ROOM_LIST_UPDATED"));
    }

    private void deleteRoom(String roomId) {
        // 메모리에서 제거
        chatRooms.remove(roomId);
        log.info("채팅방 삭제됨: {}", roomId);
    }

    public void handleDisconnect(String userId) {
        chatRooms.values().stream()
                .filter(room -> room.isParticipant(userId))
                .findFirst()
                .ifPresent(room -> userLeave(room.getRoomId(), userId));
    }

    public ChatRoom getRoom(String roomId) {
        return chatRooms.get(roomId);
    }

    public List<ChatRoom> getAllRooms() {
        return List.copyOf(chatRooms.values());
    }

    // ================== 헬퍼 메소드 ================== //

    private SimpUserRegistry getSimpUserRegistry() {
        try {
            return applicationContext.getBean(SimpUserRegistry.class);
        } catch (Exception e) {
            log.warn("SimpUserRegistry를 가져올 수 없습니다: {}", e.getMessage());
            return null;
        }
    }

    private boolean canPlayerChat(String roomId, String playerId) {
        Game game = gameService.getGameByRoomId(roomId);
        if (game == null || game.getStatus() != GameStatus.IN_PROGRESS) {
            return true; // 게임이 없거나 진행 중이 아니면 항상 채팅 가능
        }

        GamePlayer player = game.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst().orElse(null);

        if (player == null || !player.isAlive()) {
            return false; // 죽은 플레이어는 채팅 불가
        }

        if (game.getGamePhase() == GamePhase.DAY_FINAL_DEFENSE) {
            return playerId.equals(game.getVotedPlayerId());
        }

        return true;
    }

    private void sendErrorMessageToUser(String userId, String errorMessage) {
        Map<String, Object> message = Map.of(
                "type", "ERROR",
                "content", errorMessage);

        log.info("에러 메시지 전송 시도: userId={}, destination=/user/{}/queue/private", userId, userId);

        // 사용자 등록 상태 확인
        SimpUserRegistry userRegistry = getSimpUserRegistry();
        if (userRegistry != null) {
            var user = userRegistry.getUser(userId);
            if (user == null) {
                log.warn("에러 메시지 수신자가 WebSocket에 등록되지 않음: {}", userId);
                return;
            }
        }

        try {
            // userId는 로그인 아이디(Principal 이름)여야 함
            messagingTemplate.convertAndSendToUser(userId, "/queue/private", message);
            log.info("에러 메시지 전송 성공: userId={}", userId);
        } catch (Exception e) {
            log.error("에러 메시지 전송 실패: userId={}, error: {}", userId, e.getMessage());
        }
    }
}
