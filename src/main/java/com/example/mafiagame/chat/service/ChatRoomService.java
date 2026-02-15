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
import com.example.mafiagame.user.domain.Users;
import com.example.mafiagame.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisTemplate;
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
    private final RedisTemplate<String, ChatRoom> chatRoomRedisTemplate;
    private final RedissonClient redissonClient;

    private static final String CHAT_LOG_PREFIX = "chat:logs:";
    private static final String ROOM_KEY_PREFIX = "chatroom:";
    private static final String ROOM_LOCK_PREFIX = "lock:room:";
    private static final int AI_GENERATION_MSG_COUNT = 10;

    private final Map<String, List<String>> chatLogBuffer = new ConcurrentHashMap<>();
    private final Map<String, Integer> messageCounters = new ConcurrentHashMap<>();

    // ================== 메시지 처리 ================== //

    public void processAndBroadcastMessage(ChatMessage chatMessage, String senderId) {
        Users sender = userService.getUserByLoginId(senderId);

        // 보안을 위해 발신자 정보 서버에서 설정
        chatMessage.setSenderId(sender.getUserLoginId());
        chatMessage.setSenderName(sender.getNickname());

        // 채팅 권한 검사 (GameService에 위임)
        if (!gameQueryService.canPlayerChat(chatMessage.getRoomId(), senderId)) {
            sendErrorMessageToUser(senderId, "지금은 채팅을 할 수 없습니다.");
            return;
        }

        // 게임 진행 상태 확인
        Game game = gameQueryService.getGameByRoomId(chatMessage.getRoomId());
        if (game != null) {
            GameState gameState = gameQueryService.getGameState(game.getGameId());
            if (gameState != null && gameState.getGamePhase() == GamePhase.NIGHT_ACTION) {
                // 이미 canPlayerChat에서 마피아 여부는 확인됨 (마피아만 통과)

                // 메시지 타입을 MAFIA_CHAT으로 변경
                chatMessage.setType(MessageType.MAFIA_CHAT);

                // 생존한 마피아들에게만 개별 전송
                gameState.getPlayers().stream()
                        .filter(p -> p.getRole() == PlayerRole.MAFIA || !p.isAlive())
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
     */
    private void bufferAndFlushChatLog(String roomId, String senderName, String content) {
        String logEntry = senderName + ": " + content;

        // 버퍼에 추가
        chatLogBuffer.computeIfAbsent(roomId, k -> new ArrayList<>()).add(logEntry);
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
            // 이전 로그 삭제
            stringRedisTemplate.delete(key);
            // 일괄 저장 (rightPushAll)
            stringRedisTemplate.opsForList().rightPushAll(key, buffer);
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
        String recipientId = chatMessage.getRecipient();
        if (recipientId == null || recipientId.trim().isEmpty()) {
            messageBroadcaster.sendError(senderId, "메세지를 보낼 대상을 지정해주세요.");
            return;
        }

        Users sender = userService.getUserByLoginId(senderId);
        chatMessage.setSenderId(sender.getUserLoginId());
        chatMessage.setSenderName(sender.getNickname());

        messageBroadcaster.sendPrivateMessage(recipientId, chatMessage);
    }

    // ================== 채팅방 및 사용자 관리 ================== //

    public ChatRoom createRoom(CreateRoomRequest request, String hostId) {
        Users host = userService.getUserByLoginId(hostId);
        ChatRoom room = request.toEntity(host);

        room.addParticipant(request.toHostParticipant(host));
        chatRoomRedisTemplate.opsForValue().set(ROOM_KEY_PREFIX + room.getRoomId(), room);
        return room;
    }

    public void userJoin(JoinRoomRequest request) {
        String lockKey = ROOM_LOCK_PREFIX + request.roomId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[userJoin] 락 획득 실패: roomId={}, userId={}", request.roomId(), request.userId());
                sendErrorMessageToUser(request.userId(), "잠시 후 다시 시도해주세요.");
                return;
            }

            ChatRoom room = getRoom(request.roomId());
            if (room == null) {
                sendErrorMessageToUser(request.userId(), "채팅방이 존재하지 않습니다.");
                return;
            }

            Users user = userService.getUserByLoginId(request.userId());
            room.addParticipant(request.toParticipant(user));

            saveRoom(room);

            String content = createJoinMessage(user, room.getHostId().equals(user.getUserLoginId()));
            ChatMessage joinMessage = ChatMessage.userJoined(room, content);

            messageBroadcaster.broadcastToRoom(request.roomId(), joinMessage);
            messageBroadcaster.notifyRoomListUpdated();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[userJoin] 인터럽트 발생: roomId={}", request.roomId(), e);
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
        String lockKey = ROOM_LOCK_PREFIX + request.roomId();
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(5, 10, TimeUnit.SECONDS)) {
                log.warn("[userLeave] 락 획득 실패: roomId={}, userId={}", request.roomId(), request.userId());
                sendErrorMessageToUser(request.userId(), "잠시 후 다시 시도해주세요.");
                return;
            }

            ChatRoom room = getRoom(request.roomId());
            if (room == null)
                return;

            // 퇴장 가능 여부 확인 (GameService에 위임)
            if (!gameQueryService.canPlayerLeaveRoom(request.roomId(), request.userId())) {
                log.warn("게임 진행 중 퇴장 시도 차단: userId={}, roomId={}", request.userId(), request.roomId());
                sendErrorMessageToUser(request.userId(), "게임이 진행 중입니다. 게임이 끝날 때까지 방을 나갈 수 없습니다.");
                return;
            }

            String leftUserName = room.removeParticipant(request.userId());
            if (leftUserName == null)
                return; // 방에 없는 유저가 나가는 경우

            if (room.getParticipants().isEmpty()) {
                // 아무도 없으면 방 삭제
                deleteRoom(request.roomId());
            } else {
                // Redis에 변경된 방 정보 저장
                saveRoom(room);
                ChatMessage leaveMessage = ChatMessage.userLeft(room, leftUserName);
                messageBroadcaster.broadcastToRoom(request.roomId(), leaveMessage);
            }
            messageBroadcaster.notifyRoomListUpdated();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[userLeave] 인터럽트 발생: roomId={}", request.roomId(), e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    // 방 저장
    private void saveRoom(ChatRoom room) {
        chatRoomRedisTemplate.opsForValue().set(ROOM_KEY_PREFIX + room.getRoomId(), room);
    }

    // 방 삭제
    private void deleteRoom(String roomId) {
        chatRoomRedisTemplate.delete(ROOM_KEY_PREFIX + roomId);
        // 채팅 로그도 함께 삭제
        stringRedisTemplate.delete(CHAT_LOG_PREFIX + roomId);
    }

    public void handleDisconnect(String userId) {
        Set<String> keys = chatRoomRedisTemplate.keys(ROOM_KEY_PREFIX + "*");
        if (keys == null)
            return;

        keys.stream()
                .map(key -> chatRoomRedisTemplate.opsForValue().get(key))
                .filter(Objects::nonNull)
                .filter(room -> room.isParticipant(userId))
                .findFirst()
                .ifPresent(room -> {
                    // 퇴장 가능 여부 GameService에 위임
                    if (!gameQueryService.canPlayerLeaveRoom(room.getRoomId(), userId)) {
                        log.info("게임 진행 중 - 재연결 대기: userId={}, roomId={}", userId, room.getRoomId());
                        return;
                    }
                    log.info("연결 해제 - 퇴장 처리: userId={}, roomId={}", userId, room.getRoomId());
                    userLeave(LeaveRoomRequest.of(room.getRoomId(), userId));
                });
    }

    public ChatRoom getRoom(String roomId) {
        return chatRoomRedisTemplate.opsForValue().get(ROOM_KEY_PREFIX + roomId);
    }

    public List<ChatRoom> getAllRooms() {
        Set<String> keys = chatRoomRedisTemplate.keys(ROOM_KEY_PREFIX + "*");
        if (keys == null)
            return List.of();

        return keys.stream()
                .map(key -> chatRoomRedisTemplate.opsForValue().get(key))
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 유저가 현재 참여 중인 ChatRoom 조회
     */
    public ChatRoom findRoomByUserId(String userId) {
        Set<String> keys = chatRoomRedisTemplate.keys(ROOM_KEY_PREFIX + "*");
        if (keys == null)
            return null;

        return keys.stream()
                .map(key -> chatRoomRedisTemplate.opsForValue().get(key))
                .filter(Objects::nonNull)
                .filter(room -> room.isParticipant(userId))
                .findFirst()
                .orElse(null);
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
}
