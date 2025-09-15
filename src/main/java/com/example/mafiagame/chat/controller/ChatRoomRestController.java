package com.example.mafiagame.chat.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.service.ChatRoomService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    // 새 방 생성
    @PostMapping
    public ResponseEntity<ChatRoom> createRoom(@RequestBody Map<String, Object> request) {
        String roomName = (String) request.get("roomName");
        String userId = (String) request.get("userId");

        ChatRoom room = chatRoomService.createRoom(roomName, userId);
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
            // 방 정보를 반환하여 클라이언트에서 방장 정보 등을 확인할 수 있도록 함
            ChatRoom room = chatRoomService.getRoom(roomId);
            return ResponseEntity.ok(Map.of(
                "message", "방 입장 성공",
                "room", room
            ));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "방 입장 실패"));
        }
    }

    // 방 나가기
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<Map<String, Object>> leaveRoom(@PathVariable String roomId,
                                                       @RequestBody Map<String, String> request) {
        log.info("🔍 방 나가기 API 호출: roomId={}, request={}", roomId, request);
        
        String userId = request.get("userId");
        log.info("🔍 추출된 userId: {}", userId);
        
        if (userId == null || userId.isEmpty()) {
            log.error("❌ userId가 null이거나 비어있습니다.");
            return ResponseEntity.badRequest().body(Map.of("error", "userId가 필요합니다."));
        }

        boolean success = chatRoomService.leaveRoom(roomId, userId);
        log.info("🔍 방 나가기 결과: {}", success);
        
        if (success) {
            return ResponseEntity.ok(Map.of("message", "방 나가기 성공"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "방 나가기 실패"));
        }
    }

    // 방장 위임
    @PostMapping("/{roomId}/transfer-host")
    public ResponseEntity<Map<String, Object>> transferHost(@PathVariable String roomId,
                                                           @RequestBody Map<String, String> request) {
        String currentHostId = request.get("currentHostId");
        String newHostId = request.get("newHostId");
        
        log.info("🔍 방장 위임 요청: roomId={}, currentHostId={}, newHostId={}", roomId, currentHostId, newHostId);
        
        boolean success = chatRoomService.transferHost(roomId, currentHostId, newHostId);
        
        if (success) {
            return ResponseEntity.ok(Map.of("message", "방장 위임 성공"));
        } else {
            return ResponseEntity.badRequest().body(Map.of("error", "방장 위임 실패"));
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
