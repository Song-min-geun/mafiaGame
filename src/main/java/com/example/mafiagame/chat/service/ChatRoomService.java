package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.dto.MessageType;
import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.service.UserService;
import com.example.mafiagame.game.domain.PlayerRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import com.example.mafiagame.game.service.SuggestionService;
import com.example.mafiagame.game.repository.GameStateRepository;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
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
    private final SuggestionService suggestionService;
    private final GameStateRepository gameStateRepository;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String CHAT_LOG_PREFIX = "chat:logs:";
    private static final int MAX_CHAT_LOGS = 20;
    private static final int AI_GENERATION_MSG_COUNT = 10; // 10개 메시지마다 AI 생성

    // 메시지 버퍼: roomId -> List<logEntry>
    private final Map<String, List<String>> chatLogBuffer = new ConcurrentHashMap<>();
    private final Map<String, Integer> messageCounters = new ConcurrentHashMap<>();

    // ================== 메시지 처리 (핵심 로직) ================== //

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

        // 게임 진행 상태 확인 (밤 페이즈 마피아 채팅 분기)
        Game game = gameService.getGameByRoomId(chatMessage.getRoomId());
        if (game != null) {
            GameState gameState = gameService.getGameState(game.getGameId());
            if (gameState != null && gameState.getGamePhase() == GamePhase.NIGHT_ACTION) {
                // 이미 canPlayerChat에서 마피아 여부는 확인됨 (마피아만 통과)

                // 메시지 타입을 MAFIA_CHAT으로 변경
                chatMessage.setType(MessageType.MAFIA_CHAT);

                // 생존한 마피아들에게만 개별 전송
                gameState.getPlayers().stream()
                        .filter(p -> p.getRole() == PlayerRole.MAFIA && p.isAlive())
                        .forEach(mafia -> {
                            try {
                                messagingTemplate.convertAndSendToUser(mafia.getPlayerId(), "/queue/private",
                                        chatMessage);
                            } catch (Exception e) {
                                log.error("마피아 채팅 전송 실패: to {}", mafia.getPlayerId(), e);
                            }
                        });
                return; // 브로드캐스트 하지 않음
            }
        }

        messagingTemplate.convertAndSend("/topic/room." + chatMessage.getRoomId(), chatMessage);

        // 채팅 로그 버퍼에 추가 (10개 모이면 Redis에 일괄 저장 + AI 호출)
        bufferAndFlushChatLog(chatMessage.getRoomId(), chatMessage.getSenderName(), chatMessage.getContent());
    }

    /**
     * 채팅 로그를 메모리 버퍼에 추가하고, 10개가 되면 Redis에 일괄 저장
     */
    private void bufferAndFlushChatLog(String roomId, String senderName, String content) {
        String logEntry = senderName + ": " + content;

        // 버퍼에 추가
        chatLogBuffer.computeIfAbsent(roomId, k -> new java.util.ArrayList<>()).add(logEntry);
        int count = messageCounters.merge(roomId, 1, Integer::sum);

        // 10개 모이면 Redis에 일괄 저장 후 AI 호출
        if (count >= AI_GENERATION_MSG_COUNT) {
            flushChatLogBuffer(roomId);
        }
    }

    /**
     * 버퍼의 채팅 로그를 Redis에 일괄 저장하고 AI 추천 갱신 트리거
     */
    private synchronized void flushChatLogBuffer(String roomId) {
        List<String> buffer = chatLogBuffer.remove(roomId);
        messageCounters.remove(roomId);

        if (buffer == null || buffer.isEmpty())
            return;

        String key = CHAT_LOG_PREFIX + roomId;
        try {
            // 일괄 저장 (rightPushAll)
            stringRedisTemplate.opsForList().rightPushAll(key, buffer);
            // 최근 20개만 유지
            stringRedisTemplate.opsForList().trim(key, -MAX_CHAT_LOGS, -1);
            log.info("채팅 로그 일괄 저장 완료: roomId={}, count={}", roomId, buffer.size());
        } catch (Exception e) {
            log.error("Redis 채팅 로그 저장 실패: roomId={}", roomId, e);
        }

        // AI 추천 갱신 트리거
        triggerAiSuggestionUpdate(roomId);
    }

    private void triggerAiSuggestionUpdate(String roomId) {
        // roomId로 GameState 조회하여 gameId와 phase 획득
        gameStateRepository.findByRoomId(roomId).ifPresent(gameState -> {
            // 현재 페이즈가 토론이나 투표 등 대화가 중요한 페이즈인지 확인 (선택사항)
            if (gameState.getGamePhase() == GamePhase.DAY_DISCUSSION ||
                    gameState.getGamePhase() == GamePhase.NIGHT_ACTION) {
                suggestionService.generateAiSuggestionsAsync(gameState.getGameId(), gameState.getGamePhase());
            }
        });
    }

    /**
     * 최근 채팅 로그 조회 (AI 분석용)
     */
    public List<String> getRecentChatLogs(String roomId) {
        String key = CHAT_LOG_PREFIX + roomId;
        List<String> logs = stringRedisTemplate.opsForList().range(key, 0, -1);
        return logs != null ? logs : List.of();
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

        // 사용자 등록 상태 확인
        SimpUserRegistry userRegistry = getSimpUserRegistry();
        if (userRegistry != null) {
            var recipientUser = userRegistry.getUser(recipientId);
            if (recipientUser == null) {
                log.warn("수신자가 WebSocket에 등록되지 않음: {}", recipientId);
                // log.info("현재 등록된 사용자들: {}", userRegistry.getUsers());
                sendErrorMessageToUser(senderId, "수신자가 온라인이 아닙니다.");
                return;
            }

            // log.info("수신자 등록 확인됨: {} (세션 수: {})", recipientId,
            // recipientUser.getSessions().size());
        } else {
            log.warn("SimpUserRegistry를 사용할 수 없어 사용자 등록 상태를 확인할 수 없습니다.");
        }

        try {
            messagingTemplate.convertAndSendToUser(recipientId, "/queue/private", chatMessage);
            // log.info("개인 메시지 전송 성공: from {} to {}", senderId, recipientId);
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
        // log.info("채팅방 생성됨: {} (호스트: {})", room.getRoomId(), host.getNickname());
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
                .type(MessageType.USER_JOINED)
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

        // 게임 진행 중이면 살아있는 플레이어는 퇴장 불가 (죽은 플레이어는 허용)
        Game game = gameService.getGameByRoomId(roomId);
        if (game != null) {
            GameState gameState = gameService.getGameState(game.getGameId());
            if (gameState != null) {
                boolean isPlayerAlive = gameState.getPlayers().stream()
                        .anyMatch(p -> p.getPlayerId().equals(userId) && p.isAlive());
                if (isPlayerAlive) {
                    log.warn("게임 진행 중 퇴장 시도 차단 (생존 플레이어): userId={}, roomId={}", userId, roomId);
                    sendErrorMessageToUser(userId, "게임이 진행 중입니다. 게임이 끝날 때까지 방을 나갈 수 없습니다.");
                    return;
                }
                log.info("죽은 플레이어 퇴장 허용: userId={}, roomId={}", userId, roomId);
            }
        }

        String leftUserName = room.removeParticipant(userId);
        if (leftUserName == null)
            return; // 방에 없는 유저가 나가는 경우

        if (room.getParticipants().size() > 1) {
            ChatMessage leaveMessage = ChatMessage.builder()
                    .type(MessageType.USER_LEFT)
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
        // log.info("채팅방 삭제됨: {}", roomId);
    }

    public void handleDisconnect(String userId) {
        chatRooms.values().stream()
                .filter(room -> room.isParticipant(userId))
                .findFirst()
                .ifPresent(room -> {
                    // 게임 진행 중인 방에서는 살아있는 플레이어만 재연결 대기 (죽은 플레이어는 퇴장 처리)
                    Game game = gameService.getGameByRoomId(room.getRoomId());
                    if (game != null) {
                        GameState gameState = gameService.getGameState(game.getGameId());
                        if (gameState != null) {
                            boolean isPlayerAlive = gameState.getPlayers().stream()
                                    .anyMatch(p -> p.getPlayerId().equals(userId) && p.isAlive());
                            if (isPlayerAlive) {
                                log.info("게임 진행 중 - 재연결 대기 (생존 플레이어): userId={}, roomId={}", userId, room.getRoomId());
                                return;
                            }
                            // 죽은 플레이어는 disconnect 시 퇴장 처리
                            log.info("죽은 플레이어 disconnect - 퇴장 처리: userId={}, roomId={}", userId, room.getRoomId());
                        }
                    }
                    userLeave(room.getRoomId(), userId);
                });
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
        // 1. 진행 중인 게임 확인 (DB에서 gameId 조회)
        Game game = gameService.getGameByRoomId(roomId);
        if (game == null) {
            return true; // 게임이 없으면 채팅 가능 (대기방 등)
        }

        // 2. 실시간 상태 조회 (Redis)
        GameState gameState = gameService.getGameState(game.getGameId());
        if (gameState == null) {
            return true; // Redis에 상태가 없으면(예외 상황) 채팅 허용
        }

        // 3. 플레이어 상태 확인
        GamePlayerState player = gameState.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst().orElse(null);

        if (player == null || !player.isAlive()) {
            return false; // 죽은 플레이어는 채팅 불가
        }

        // 4. 최후 변론 단계 확인
        if (gameState.getGamePhase() == GamePhase.DAY_FINAL_DEFENSE) {
            return playerId.equals(gameState.getVotedPlayerId());
        }

        // 5. 밤 페이즈 (마피아만 대화 가능)
        if (gameState.getGamePhase() == GamePhase.NIGHT_ACTION) {
            return player.getRole() == PlayerRole.MAFIA;
        }

        return true;
    }

    private void sendErrorMessageToUser(String userId, String errorMessage) {
        Map<String, Object> message = Map.of(
                "type", "ERROR",
                "content", errorMessage);

        // log.info("에러 메시지 전송 시도: userId={}, destination=/user/{}/queue/private",
        // userId, userId);

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
            // log.info("에러 메시지 전송 성공: userId={}", userId);
        } catch (Exception e) {
            log.error("에러 메시지 전송 실패: userId={}, error: {}", userId, e.getMessage());
        }
    }
}
