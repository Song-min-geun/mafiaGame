package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.request.CreateRoomRequest;
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
    private final com.example.mafiagame.game.service.GameService gameService;

    @PostMapping
    public ResponseEntity<ChatRoom> createRoom(@RequestBody Map<String, String> request,
            Authentication authentication) {
        String roomName = request.get("roomName");
        String hostId = authentication.getName();
        ChatRoom room = chatRoomService.createRoom(new CreateRoomRequest(roomName, hostId));
        return ResponseEntity.ok(room);
    }

    @GetMapping
    public ResponseEntity<List<ChatRoom>> getAllRooms() {
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        rooms.forEach(room -> {
            boolean isPlaying = gameService.getGameByRoomId(room.getRoomId()) != null;
            room.setPlaying(isPlaying);
        });
        return ResponseEntity.ok(rooms);
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