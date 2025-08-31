package com.example.mafiagame.chat.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.domain.ChatUser;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ChatRoomService {

    // 채팅룸 저장소 (실제로는 데이터베이스 사용)
    private final Map<String, ChatRoom> chatRooms = new ConcurrentHashMap<>();
    
    // 사용자별 현재 방 매핑
    private final Map<String, String> userRoomMap = new ConcurrentHashMap<>();

    // 새 방 생성
    public ChatRoom createRoom(String roomName, String hostId, int maxPlayers) {
        String roomId = generateRoomId();
        
        ChatRoom room = ChatRoom.builder()
                .roomId(roomId)
                .roomName(roomName)
                .hostId(hostId)
                .maxPlayers(maxPlayers)
                .participants(new ArrayList<>())
                .isGameActive(false)
                .build();
        chatRooms.put(roomId, room);
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
        ChatRoom room = chatRooms.get(roomId);
        if (room == null) {
            return false;
        }

        // 사용자가 이미 다른 방에 있으면 먼저 나가기
        String currentRoomId = userRoomMap.get(userId);
        if (currentRoomId != null && !currentRoomId.equals(roomId)) {
            leaveRoom(currentRoomId, userId);
        }

        // 이미 해당 방에 있는지 확인
        if (room.isUserInRoom(userId)) {
            return false;
        }

        // 방이 가득 찼는지 확인
        if (room.isFull()) {
            return false;
        }

        // 참가자 추가
        ChatUser participant = ChatUser.builder()
                .userId(userId)
                .userName(userName)
                .isOnline(true)
                .lastSeen(new Date())
                .build();

        room.addParticipant(participant);
        userRoomMap.put(userId, roomId);
        
        return true;
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
