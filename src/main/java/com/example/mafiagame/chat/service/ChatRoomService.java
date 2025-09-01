package com.example.mafiagame.chat.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import com.example.mafiagame.user.service.UserService;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ChatRoomService {

    // 채팅룸 저장소 (실제로는 데이터베이스 사용)
    private final Map<String, ChatRoom> chatRooms = new ConcurrentHashMap<>();
    
    // 사용자별 현재 방 매핑
    private final Map<String, String> userRoomMap = new ConcurrentHashMap<>();
    private final UserService userService;

    public ChatRoomService(UserService userService) {
        this.userService = userService;
    }

    // 새 방 생성
    public ChatRoom createRoom(String roomName, String userId) {
        String roomId = generateRoomId();
        
        ChatRoom room = ChatRoom.builder()
                .roomId(roomId)
                .roomName(roomName)
                .hostId(userId)  // 방장은 방을 만든 사용자
                .maxPlayers(8)
                .participants(new ArrayList<>())
                .isGameActive(false)
                .build();
        chatRooms.put(roomId, room);
        
        // 방장을 자동으로 방에 입장시킴 (직접 추가)
        // 실제 닉네임을 가져오기 위해 UserService 사용
        String hostName = "방장";  // 기본값
        try {
            var user = userService.getUserByLoginId(userId);
            if (user != null && user.getNickname() != null) {
                hostName = user.getNickname();
            }
        } catch (Exception e) {
            log.warn("사용자 정보 조회 실패: {}", userId);
        }
        
        ChatUser host = ChatUser.builder()
                .userId(userId)
                .userName(hostName)
                .isHost(true)  // 방장은 항상 true
                .build();
        
        room.addParticipant(host);
        userRoomMap.put(userId, roomId);
        
        return room;
    }

    // 방 조회
    public ChatRoom getRoom(String roomId) {
        return chatRooms.get(roomId);
    }

    // 모든 방 목록 조회
    public List<ChatRoom> getAllRooms() {
        return new ArrayList<>(chatRooms.values());
    }

    // 방 입장
    public boolean joinRoom(String roomId, String userId, String userName) {
        log.info("=== 방 입장 시도 ===");
        log.info("roomId: {}", roomId);
        log.info("userId: {}", userId);
        log.info("userName: {}", userName);
        
        ChatRoom room = chatRooms.get(roomId);
        if (room == null) {
            log.warn("방을 찾을 수 없음: {}", roomId);
            return false;
        }
        log.info("방 찾음: {}", room.getRoomName());

        // 사용자가 이미 다른 방에 있으면 먼저 나가기
        String currentRoomId = userRoomMap.get(userId);
        if (currentRoomId != null && !currentRoomId.equals(roomId)) {
            log.info("다른 방에서 나가기: {} -> {}", currentRoomId, roomId);
            leaveRoom(currentRoomId, userId);
        }

        // 이미 해당 방에 있는지 확인
        if (room.isUserInRoom(userId)) {
            log.warn("이미 방에 있음: {}", userId);
            return false;
        }

        // 방이 가득 찼는지 확인
        if (room.isFull()) {
            log.warn("방이 가득 참: {}", roomId);
            return false;
        }

        // 참가자 추가 (방장 여부는 room.getHostId()와 userId 비교로 결정)
        ChatUser participant = ChatUser.builder()
                .userId(userId)
                .userName(userName)
                .isHost(room.getHostId().equals(userId))  // 방장 여부 설정
                .build();

        log.info("참가자 추가: {} (방장: {})", userName, participant.isHost());
        
        room.addParticipant(participant);
        userRoomMap.put(userId, roomId);
        
        log.info("방 입장 성공: {}", roomId);
        return true;
    }



    public String getParticipantName(String roomId, String userId) {
    ChatRoom room = chatRooms.get(roomId);
    if (room == null || room.getParticipants() == null) {
        return "Unknown";
    }
    return room.getParticipants().stream()
            .filter(p -> p.getUserId().equals(userId))
            .findFirst()
            .map(ChatUser::getUserName)
            .orElse("Unknown");
    }

    // 방 나가기
    public boolean leaveRoom(String roomId, String userId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room == null) {
            return false;
        }

        room.removeParticipant(userId);
        userRoomMap.remove(userId);

        // 방이 비었으면 방 삭제
        if (room.getParticipants().isEmpty()) {
            deleteRoom(roomId);
        }

        return true;
    }

    // 게임 시작
    public boolean startGame(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room == null || !room.canStartGame()) {
            return false;
        }

        room.startGame();
        return true;
    }

    // 게임 종료
    public boolean endGame(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room == null) {
            return false;
        }

        room.endGame();
        return true;
    }

    // 사용자가 현재 있는 방 조회
    public String getUserRoom(String userId) {
        return userRoomMap.get(userId);
    }


    // 사용자가 특정 방에 있는지 확인
    public boolean isUserInRoom(String userId, String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        return room != null && room.isUserInRoom(userId);
    }

    // 방의 모든 참여자 ID 목록 조회
    public List<String> getRoomParticipants(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room == null || room.getParticipants() == null) {
            return new ArrayList<>();
        }
        
        return room.getParticipants().stream()
                .map(ChatUser::getUserId)
                .collect(Collectors.toList());
    }

    // 방 ID 생성
    private String generateRoomId() {
        return "room_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);
    }

    // 방 삭제
    public void deleteRoom(String roomId) {
        ChatRoom room = chatRooms.get(roomId);
        if (room != null) {
            // 모든 참가자 제거
            for (ChatUser participant : room.getParticipants()) {
                userRoomMap.remove(participant.getUserId());
            }
            chatRooms.remove(roomId);
        }
    }




}
