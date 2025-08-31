package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    // 새 방 생성
    @PostMapping
    public ResponseEntity<ChatRoom> createRoom(@RequestBody Map<String, Object> request) {
        String roomName = (String) request.get("roomName");
        String hostId = (String) request.get("hostId");
        Integer maxPlayers = (Integer) request.getOrDefault("maxPlayers", 8);

        ChatRoom room = chatRoomService.createRoom(roomName, hostId, maxPlayers);
        return ResponseEntity.ok(room);
    }

    // 모든 방 목록 조회
    @GetMapping
    public ResponseEntity<List<ChatRoom>> getAllRooms() {
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        return ResponseEntity.ok(rooms);
    }

    // 특정 방 조회
    @GetMapping("/{roomId}")
    public ResponseEntity<ChatRoom> getRoom(@PathVariable String roomId) {
        ChatRoom room = chatRoomService.getRoom(roomId);
        if (room != null) {
            return ResponseEntity.ok(room);
        }
        return ResponseEntity.notFound().build();
    }

    // 방 입장
    @PostMapping("/{roomId}/join")
    public ResponseEntity<Map<String, Object>> joinRoom(@PathVariable String roomId, 
                                                      @RequestBody Map<String, String> request) {
        String userId = request.get("userId");
        String userName = request.get("userName");

        boolean success = chatRoomService.joinRoom(roomId, userId, userName);
        
        if (success) {
            return ResponseEntity.ok(Map.of("message", "방 입장 성공"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "방 입장 실패"));
        }
    }

    // 방 나가기
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Map<String, Object>> leaveRoom(@PathVariable String roomId, 
                                                       @RequestBody Map<String, String> request) {
        String userId = request.get("userId");

        boolean success = chatRoomService.leaveRoom(roomId, userId);
        
        if (success) {
            return ResponseEntity.ok(Map.of("message", "방 나가기 성공"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "방 나가기 실패"));
        }
    }

    // 게임 시작
    @PostMapping("/{roomId}/start-game")
    public ResponseEntity<Map<String, Object>> startGame(@PathVariable String roomId) {
        boolean success = chatRoomService.startGame(roomId);
        
        if (success) {
            return ResponseEntity.ok(Map.of("message", "게임 시작"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "게임 시작 실패"));
        }
    }

    // 게임 종료
    @PostMapping("/{roomId}/end-game")
    public ResponseEntity<Map<String, Object>> endGame(@PathVariable String roomId) {
        boolean success = chatRoomService.endGame(roomId);
        
        if (success) {
            return ResponseEntity.ok(Map.of("message", "게임 종료"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "게임 종료 실패"));
        }
    }

    // 방 삭제
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Map<String, Object>> deleteRoom(@PathVariable String roomId) {
        chatRoomService.deleteRoom(roomId);
        return ResponseEntity.ok(Map.of("message", "방 삭제 완료"));
    }
}
