package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.ChatMessage;
import com.example.mafiagame.chat.dto.MessageType;
import com.example.mafiagame.chat.dto.request.CreateRoomRequest;
import com.example.mafiagame.chat.dto.request.JoinRoomRequest;
import com.example.mafiagame.chat.dto.request.LeaveRoomRequest;
import com.example.mafiagame.game.domain.entity.Game;
import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.PlayerRole;
import com.example.mafiagame.game.service.GameQueryService;
import com.example.mafiagame.game.service.SuggestionService;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.global.error.CommonException;
import com.example.mafiagame.global.error.ErrorCode;
import com.example.mafiagame.global.service.RedisService;
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    private final UserService userService;
    private final GameQueryService gameQueryService;
    private final WebSocketMessageBroadcaster messageBroadcaster;
    private final SuggestionService suggestionService;
    private final GameStateRepository gameStateRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final RedisService redisService;

    private static final String CHAT_LOG_PREFIX = "chat:logs:";
    private static final String ROOM_LOCK_PREFIX = "lock:room:";
    private static final int AI_GENERATION_MSG_COUNT = 10;
    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final DefaultRedisScript<Long> REPLACE_CHAT_LOG_SCRIPT = new DefaultRedisScript<>(
            "redis.call('DEL', KEYS[1]); " +
                    "for i = 1, #ARGV do redis.call('RPUSH', KEYS[1], ARGV[i]); end " +
                    "return #ARGV",
            Long.class);

    private final Map<String, List<String>> chatLogBuffer = new ConcurrentHashMap<>();
    private final Map<String, Integer> messageCounters = new ConcurrentHashMap<>();
    private final Object bufferLock = new Object();

    // ================== 메시지 처리 ================== //

    public void processAndBroadcastMessage(ChatMessage chatMessage, String senderId) {
        if (chatMessage == null) {
            log.warn("[sendMessage] payload is null: userId={}", senderId);
            sendErrorMessageToUser(senderId, "메시지 형식이 올바르지 않습니다.");
            return;
        }

        ChatRoom room = resolveRoomForUser(chatMessage.getRoomId(), senderId, "메시지 전송");
        if (room == null) {
            return;
        }

        String normalizedContent = normalizeValue(chatMessage.getContent());
        if (!validateMessageContent(normalizedContent, senderId)) {
            return;
        }

        Users sender = userService.getUserByLoginId(senderId);

        // 보안을 위해 발신자 정보 서버에서 설정
        chatMessage.setSenderId(sender.getUserLoginId());
        chatMessage.setSenderName(sender.getNickname());
        chatMessage.setRoomId(room.getRoomId());
        chatMessage.setRoomName(room.getRoomName());
        chatMessage.setContent(normalizedContent);
        chatMessage.setTimestamp(System.currentTimeMillis());
        chatMessage.setType(MessageType.CHAT);

        // 채팅 권한 검사 (GameService에 위임)
        if (!gameQueryService.canPlayerChat(room.getRoomId(), senderId)) {
            sendErrorMessageToUser(senderId, "지금은 채팅을 할 수 없습니다.");
            return;
        }

        // 게임 진행 상태 확인
        Game game = gameQueryService.getGameByRoomId(room.getRoomId());
        if (game != null) {
            GameState gameState = gameQueryService.getGameState(game.getGameId());
            if (gameState != null && gameState.getGamePhase() == GamePhase.NIGHT_ACTION) {
                // 이미 canPlayerChat에서 마피아 여부는 확인됨 (마피아만 통과)

                // 메시지 타입을 MAFIA_CHAT으로 변경
                chatMessage.setType(MessageType.MAFIA_CHAT);

                // 생존한 마피아들에게만 개별 전송
                gameState.getPlayers().stream()
                        .filter(p -> p.isAlive() && p.getRole() == PlayerRole.MAFIA)
                        .forEach(mafia -> messageBroadcaster.sendPrivateMessage(mafia.getPlayerId(), chatMessage));
                return;
            }
        }

        messageBroadcaster.broadcastToRoom(chatMessage.getRoomId(), chatMessage);

        // 채팅 로그 버퍼에 추가 (10개 모이면 Redis에 일괄 저장 + AI 호출)
        bufferAndFlushChatLog(chatMessage.getRoomId(), chatMessage.getSenderName(), chatMessage.getContent());
    }

    /**
     * 채팅 로그를 메모리 버퍼에 추가하고, 10개가 되면 Redis에 일괄 저장
     * synchronized 블록으로 버퍼 추가 + 카운터 증가 + flush 판단을 원자적으로 처리
     */
    private void bufferAndFlushChatLog(String roomId, String senderName, String content) {
        String logEntry = senderName + ": " + content;
        List<String> bufferToFlush = null;

        synchronized (bufferLock) {
            chatLogBuffer.computeIfAbsent(roomId, k -> new ArrayList<>()).add(logEntry);
            int count = messageCounters.merge(roomId, 1, Integer::sum);

            if (count >= AI_GENERATION_MSG_COUNT) {
                bufferToFlush = chatLogBuffer.remove(roomId);
                messageCounters.remove(roomId);
            }
        }

        // Redis 저장과 AI 호출은 락 밖에서 실행 (블로킹 방지)
        if (bufferToFlush != null && !bufferToFlush.isEmpty()) {
            flushChatLogBufferDirect(roomId, bufferToFlush);
        }
    }

    /**
     * 버퍼의 채팅 로그를 Redis에 일괄 저장하고 AI 추천 갱신 트리거
     */
    private void flushChatLogBufferDirect(String roomId, List<String> buffer) {
        String key = CHAT_LOG_PREFIX + roomId;
        try {
            stringRedisTemplate.execute(
                    REPLACE_CHAT_LOG_SCRIPT,
                    List.of(key),
                    buffer.toArray());
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
            if (gameState.getGamePhase() == GamePhase.DAY_DISCUSSION) {
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
        if (chatMessage == null) {
            log.warn("[sendPrivateMessage] payload is null: userId={}", senderId);
            sendErrorMessageToUser(senderId, "메시지 형식이 올바르지 않습니다.");
            return;
        }

        ChatRoom room = resolveRoomForUser(chatMessage.getRoomId(), senderId, "개인 메시지 전송");
        if (room == null) {
            return;
        }

        String recipientId = normalizeValue(chatMessage.getRecipient());
        if (recipientId == null) {
            messageBroadcaster.sendError(senderId, "메시지를 보낼 대상을 지정해주세요.");
            return;
        }
        if (senderId.equals(recipientId)) {
            messageBroadcaster.sendError(senderId, "자기 자신에게는 메시지를 보낼 수 없습니다.");
            return;
        }
        if (!room.isParticipant(recipientId)) {
            messageBroadcaster.sendError(senderId, "같은 방에 있는 사용자에게만 메시지를 보낼 수 있습니다.");
            return;
        }

        String normalizedContent = normalizeValue(chatMessage.getContent());
        if (!validateMessageContent(normalizedContent, senderId)) {
            return;
        }

        if (!gameQueryService.canPlayerChat(room.getRoomId(), senderId)) {
            sendErrorMessageToUser(senderId, "지금은 채팅을 할 수 없습니다.");
            return;
        }

        Users sender = userService.getUserByLoginId(senderId);
        chatMessage.setSenderId(sender.getUserLoginId());
        chatMessage.setSenderName(sender.getNickname());
        chatMessage.setRecipient(recipientId);
        chatMessage.setRoomId(room.getRoomId());
        chatMessage.setRoomName(room.getRoomName());
        chatMessage.setContent(normalizedContent);
        chatMessage.setTimestamp(System.currentTimeMillis());
        chatMessage.setType(MessageType.CHAT);

        messageBroadcaster.sendPrivateMessage(recipientId, chatMessage);
    }

    // ================== 채팅방 및 사용자 관리 ================== //

    public ChatRoom createRoom(CreateRoomRequest request, String hostId) {
        Users host = userService.getUserByLoginId(hostId);
        ChatRoom room = request.toEntity(host);

        room.addParticipant(request.toHostParticipant(host));
        redisService.saveChatRoom(room);

        // RedisService를 통한 유저 세션 관리 강화 (옵션)
        try {
            redisService.saveUserSession(hostId, room.getRoomId(), null);
        } catch (Exception e) {
            redisService.deleteChatRoom(room.getRoomId());
            log.error("채팅방 생성 중 세션 저장 실패. 롤백 수행 (방 삭제): roomId={}", room.getRoomId(), e);
            throw new CommonException(ErrorCode.CHAT_ROOM_CREATE_FAILED);
        }
        return room;
    }

    public void userJoin(JoinRoomRequest request) {
        String roomId = normalizeValue(request.roomId());
        if (roomId == null) {
            sendErrorMessageToUser(request.userId(), "방 정보가 올바르지 않습니다.");
            return;
        }

        String currentRoomId = redisService.getUserRoomId(request.userId());
        if (currentRoomId != null && !currentRoomId.equals(roomId)) {
            sendErrorMessageToUser(request.userId(), "이미 다른 방에 참여 중입니다.");
            return;
        }

        String lockKey = ROOM_LOCK_PREFIX + roomId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[userJoin] 락 획득 실패: roomId={}, userId={}", roomId, request.userId());
                sendErrorMessageToUser(request.userId(), "잠시 후 다시 시도해주세요.");
                return;
            }

            ChatRoom room = getRoom(roomId);
            if (room == null) {
                sendErrorMessageToUser(request.userId(), "채팅방이 존재하지 않습니다.");
                return;
            }

            Users user = userService.getUserByLoginId(request.userId());
            boolean added = room.addParticipant(request.toParticipant(user));
            if (!added) {
                sendErrorMessageToUser(request.userId(), "채팅방이 가득 찼거나 이미 참여 중입니다.");
                return;
            }

            saveRoom(room);

            // 유저 세션 정보 업데이트
            redisService.saveUserSession(request.userId(), roomId, null);

            String content = createJoinMessage(user, room.getHostId().equals(user.getUserLoginId()));
            ChatMessage joinMessage = ChatMessage.userJoined(room, content);

            messageBroadcaster.broadcastToRoom(roomId, joinMessage);
            messageBroadcaster.notifyRoomListUpdated();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[userJoin] 인터럽트 발생: roomId={}", roomId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 방 hostId와 userId와 비교하여 메세지 전달
    private String createJoinMessage(Users user, boolean isHost) {
        return isHost
                ? user.getNickname() + "님이 방을 개설하였습니다."
                : user.getNickname() + "님이 입장하였습니다.";
    }

    public void userLeave(LeaveRoomRequest request) {
        String roomId = resolveRoomIdForLeave(request.roomId(), request.userId());
        if (roomId == null) {
            sendErrorMessageToUser(request.userId(), "방 정보가 올바르지 않습니다.");
            return;
        }

        String lockKey = ROOM_LOCK_PREFIX + roomId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[userLeave] 락 획득 실패: roomId={}, userId={}", roomId, request.userId());
                sendErrorMessageToUser(request.userId(), "잠시 후 다시 시도해주세요.");
                return;
            }

            ChatRoom room = getRoom(roomId);
            if (room == null)
                return;

            // 퇴장 가능 여부 확인 (GameService에 위임)
            if (!gameQueryService.canPlayerLeaveRoom(roomId, request.userId())) {
                log.warn("게임 진행 중 퇴장 시도 차단: userId={}, roomId={}", request.userId(), roomId);
                sendErrorMessageToUser(request.userId(), "게임이 진행 중입니다. 게임이 끝날 때까지 방을 나갈 수 없습니다.");
                return;
            }

            String previousHostId = room.getHostId();
            String leftUserName = room.removeParticipant(request.userId());
            if (leftUserName == null) {
                redisService.deleteUserSession(request.userId());
                return; // 방에 없는 유저가 나가는 경우
            }

            // 유저 세션 정보 삭제
            redisService.deleteUserSession(request.userId());

            if (room.getParticipants().isEmpty()) {
                // 아무도 없으면 방 삭제
                deleteRoom(roomId);
            } else {
                // Redis에 변경된 방 정보 저장
                saveRoom(room);
                ChatMessage leaveMessage = ChatMessage.userLeft(room, leftUserName);
                messageBroadcaster.broadcastToRoom(roomId, leaveMessage);
                if (!Objects.equals(previousHostId, room.getHostId())) {
                    messageBroadcaster.sendHostChanged(roomId, room.getHostId(), room.getHostName());
                }
            }
            messageBroadcaster.notifyRoomListUpdated();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[userLeave] 인터럽트 발생: roomId={}", roomId, e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 방 저장
    private void saveRoom(ChatRoom room) {
        redisService.saveChatRoom(room);
    }

    // 방 삭제
    private void deleteRoom(String roomId) {
        redisService.deleteChatRoom(roomId);
        // 채팅 로그도 함께 삭제
        stringRedisTemplate.delete(CHAT_LOG_PREFIX + roomId);
        clearChatLogBuffer(roomId);
    }

    public void handleDisconnect(String userId) {
        // 효율적인 세션 기반 조회로 변경 (keys 전수 조사 제거)
        String roomId = redisService.getUserRoomId(userId);
        if (roomId == null)
            return;

        ChatRoom room = getRoom(roomId);
        if (room == null)
            return;

        // 퇴장 가능 여부 GameService에 위임
        if (!gameQueryService.canPlayerLeaveRoom(roomId, userId)) {
            log.info("게임 진행 중 - 재연결 대기: userId={}, roomId={}", userId, roomId);
            return;
        }
        log.info("연결 해제 - 퇴장 처리: userId={}, roomId={}", userId, roomId);
        userLeave(LeaveRoomRequest.of(roomId, userId));
    }

    public ChatRoom getRoom(String roomId) {
        return redisService.getChatRoom(roomId);
    }

    public List<ChatRoom> getAllRooms() {
        Set<String> roomIds = redisService.getAllRoomIds();
        return roomIds.stream()
                .map(this::getRoom)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 유저가 현재 참여 중인 ChatRoom 조회
     */
    public ChatRoom findRoomByUserId(String userId) {
        // 효율적인 세션 기반 조회로 변경
        String roomId = redisService.getUserRoomId(userId);
        return roomId != null ? getRoom(roomId) : null;
    }

    // ================== 헬퍼 메소드 ================== //

    /**
     * 키워드로 채팅방 검색
     */
    public List<ChatRoom> searchRooms(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return getAllRooms();
        }

        String lowerKeyword = keyword.toLowerCase();
        return getAllRooms().stream()
                .filter(room -> room.getRoomName().toLowerCase().contains(lowerKeyword))
                .toList();
    }

    private void sendErrorMessageToUser(String userId, String errorMessage) {
        messageBroadcaster.sendError(userId, errorMessage);
    }

    private String normalizeValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean validateMessageContent(String content, String userId) {
        if (content == null) {
            sendErrorMessageToUser(userId, "메시지를 입력해주세요.");
            return false;
        }
        if (content.length() > MAX_MESSAGE_LENGTH) {
            sendErrorMessageToUser(userId, "메시지는 최대 " + MAX_MESSAGE_LENGTH + "자까지 입력할 수 있습니다.");
            return false;
        }
        return true;
    }

    private ChatRoom resolveRoomForUser(String requestedRoomId, String userId, String action) {
        String normalizedRequest = normalizeValue(requestedRoomId);
        String sessionRoomId = redisService.getUserRoomId(userId);
        String resolvedRoomId = sessionRoomId != null ? sessionRoomId : normalizedRequest;

        if (resolvedRoomId == null) {
            log.warn("[{}] roomId not found: userId={}", action, userId);
            sendErrorMessageToUser(userId, "방 정보가 올바르지 않습니다.");
            return null;
        }

        if (sessionRoomId != null && normalizedRequest != null && !sessionRoomId.equals(normalizedRequest)) {
            log.warn("[{}] roomId mismatch: userId={}, requestRoomId={}, sessionRoomId={}", action, userId,
                    normalizedRequest, sessionRoomId);
            sendErrorMessageToUser(userId, "요청한 방 정보가 올바르지 않습니다.");
            return null;
        }

        ChatRoom room = getRoom(resolvedRoomId);
        if (room == null) {
            sendErrorMessageToUser(userId, "채팅방이 존재하지 않습니다.");
            return null;
        }
        if (!room.isParticipant(userId)) {
            sendErrorMessageToUser(userId, "해당 방에 참여 중이 아닙니다.");
            return null;
        }
        return room;
    }

    private String resolveRoomIdForLeave(String requestedRoomId, String userId) {
        String normalizedRequest = normalizeValue(requestedRoomId);
        String sessionRoomId = redisService.getUserRoomId(userId);
        if (sessionRoomId != null) {
            if (normalizedRequest != null && !sessionRoomId.equals(normalizedRequest)) {
                log.warn("[userLeave] roomId mismatch: userId={}, requestRoomId={}, sessionRoomId={}", userId,
                        normalizedRequest, sessionRoomId);
            }
            return sessionRoomId;
        }
        return normalizedRequest;
    }

    private void clearChatLogBuffer(String roomId) {
        synchronized (bufferLock) {
            chatLogBuffer.remove(roomId);
            messageCounters.remove(roomId);
        }
    }
}
