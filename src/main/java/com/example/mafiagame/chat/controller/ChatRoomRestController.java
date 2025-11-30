package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    public ResponseEntity<ChatRoom> createRoom(@RequestBody Map<String, String> request, Authentication authentication) {
        String roomName = request.get("roomName");
        String hostId = authentication.getName(); // 인증된 사용자 정보 사용
        ChatRoom room = chatRoomService.createRoom(roomName, hostId);
        return ResponseEntity.ok(room);
    }

    @GetMapping
    public ResponseEntity<List<ChatRoom>> getAllRooms() {
        return ResponseEntity.ok(chatRoomService.getAllRooms());
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<?> getRoom(@PathVariable String roomId) {
        ChatRoom room = chatRoomService.getRoom(roomId);
        if (room != null) {
            return ResponseEntity.ok(Map.of("success", true, "data", room));
        } else {
            return ResponseEntity.notFound().build();
        }
    }
}