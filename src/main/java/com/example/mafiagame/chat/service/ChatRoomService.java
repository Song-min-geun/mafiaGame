package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.global.service.RedisService;
import com.example.mafiagame.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRoomService {

    private final Map<String, ChatRoom> chatRooms = new ConcurrentHashMap<>(); // 메모리 캐시 (백업용)
    private final RedisService redisService;
    private final GameService gameService;
    private final UserService userService;

    public ChatRoom createRoom(String roomId, String hostId, int maxPlayers) {
        ChatRoom room = ChatRoom.builder()
                .roomId(roomId)
                .hostId(hostId)
                .maxPlayers(maxPlayers)
                .build();

        // 호스트를 자동으로 참가자 목록에 추가
        String hostName = findUserNameByUserId(hostId);
        ChatUser host = ChatUser.builder()
                .userId(hostId)
                .userName(hostName)
                .roomId(roomId)
                .isHost(true)
                .build();
        room.getParticipants().add(host);
        
        // Redis에 저장
        redisService.saveChatRoom(room);
        // 메모리 캐시에도 저장 (백업용)
        chatRooms.put(roomId, room);
        
        log.info("채팅방 생성됨: {} (호스트: {})", roomId, hostName);
        return room;
    }

    public ChatRoom getRoom(String roomId) {
        // Redis에서 먼저 조회
        ChatRoom room = redisService.getChatRoom(roomId);
        if (room != null) {
            // 메모리 캐시도 업데이트
            chatRooms.put(roomId, room);
            return room;
        }
        
        // Redis에 없으면 메모리 캐시에서 조회
        room = chatRooms.get(roomId);
        if (room != null) {
            // Redis에 다시 저장
            redisService.saveChatRoom(room);
        }
        
        return room;
    }

    public void addParticipant(String roomId, String userId, String userName) {
        ChatRoom room = getRoom(roomId); // Redis 우선 조회
        if (room != null) {
            // 중복 참가자 체크
            boolean alreadyExists = room.getParticipants().stream()
                    .anyMatch(participant -> participant.getUserId().equals(userId));
            
            if (alreadyExists) {
                log.info("이미 참가 중인 사용자: {} -> {}", userName, roomId);
                return; // 이미 참가 중이면 추가하지 않음
            }
            
            ChatUser user = ChatUser.builder()
                    .userId(userId)
                    .userName(userName)
                    .roomId(roomId)
                    .isHost(false) // 일반 참가자는 호스트가 아님
                    .build();
            room.getParticipants().add(user);
            
            // Redis에 업데이트된 방 정보 저장
            redisService.saveChatRoom(room);
            // 메모리 캐시도 업데이트
            chatRooms.put(roomId, room);
            
            log.info("참가자 추가됨: {} -> {}", userName, roomId);
        }
    }

    public void removeParticipant(String roomId, String userId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null) {
            room.getParticipants().removeIf(user -> user.getUserId().equals(userId));
            log.info("참가자 제거됨: {} -> {}", userId, roomId);
        }
    }

    public String getParticipantName(String roomId, String userId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null) {
            return room.getParticipants().stream()
                    .filter(user -> user.getUserId().equals(userId))
                    .map(ChatUser::getUserName)
                    .findFirst()
                    .orElse("알 수 없는 사용자");
        }
        return "알 수 없는 사용자";
    }

    public List<ChatUser> getParticipants(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        return room != null ? room.getParticipants() : List.of();
    }

    public void deleteRoom(String roomId) {
        chatRooms.remove(roomId);
        log.info("채팅방 삭제됨: {}", roomId);
    }

    // 추가 필요한 메서드들
    public ChatRoom createRoom(String roomName, String hostId) {
        return createRoom(roomName, hostId, 8); // 기본값 설정
    }

    public List<ChatRoom> getAllRooms() {
        return List.copyOf(chatRooms.values());
    }

    public boolean joinRoom(String roomId, String userId, String userName) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null && !room.isFull()) {
            // 이미 참가한 사용자인지 확인
            boolean alreadyJoined = room.getParticipants().stream()
                    .anyMatch(user -> user.getUserId().equals(userId));
            
            if (!alreadyJoined) {
                addParticipant(roomId, userId, userName);
                return true;
            }
        }
        return false;
    }

    public boolean leaveRoom(String roomId, String userId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null) {
            removeParticipant(roomId, userId);
            return true;
        }
        return false;
    }

    public boolean transferHost(String roomId, String currentHostId, String newHostId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null && room.getHostId().equals(currentHostId)) {
            room.setHostId(newHostId);
            return true;
        }
        return false;
    }

    public boolean startGame(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null && room.canStartGame()) {
            // 게임 시작 로직은 GameService에서 처리
            return true;
        }
        return false;
    }

    public boolean endGame(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null && room.isGameActive()) {
            room.endGame();
            return true;
        }
        return false;
    }

    public void registerWebSocketConnection(String userId) {
        // WebSocket 연결 등록 로직
        log.info("WebSocket 연결 등록: {}", userId);
    }

    public void unregisterWebSocketConnection(String userId) {
        // WebSocket 연결 해제 로직
        log.info("WebSocket 연결 해제: {}", userId);
    }

    /**
     * 사용자 ID로 사용자 이름을 찾는 메서드
     */
    private String findUserNameByUserId(String userId) {
        try {
            // UserService를 통해 사용자 정보 조회 (userLoginId로 조회)
            var user = userService.getUserByLoginId(userId);
            if (user != null) {
                return user.getNickname();
            }
        } catch (Exception e) {
            log.warn("사용자 정보 조회 실패: userId={}, error={}", userId, e.getMessage());
        }
        
        // 사용자 정보를 찾을 수 없는 경우 기본값 반환
        return userId;
    }
}
