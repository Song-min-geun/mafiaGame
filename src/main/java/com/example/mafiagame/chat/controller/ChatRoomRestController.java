package com.example.mafiagame.chat.controller;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.dto.request.CreateRoomRequest;
import com.example.mafiagame.chat.dto.request.JoinRoomRequest;
import com.example.mafiagame.chat.dto.response.RoomListResponse;
import com.example.mafiagame.chat.dto.response.RoomResponse;
import com.example.mafiagame.chat.service.ChatRoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "ChatRoomController", description = "채팅방 관리 API")
@RestController
@RequestMapping("/api/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomRestController {

    private final ChatRoomService chatRoomService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "채팅방 생성", description = "새로운 채팅방을 생성합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "채팅방 생성 성공"),
            @ApiResponse(responseCode = "400", description = "채팅방 생성 실패")
    })
    public RoomResponse createRoom(@RequestBody CreateRoomRequest request,
            Authentication authentication) {
        ChatRoom room = chatRoomService.createRoom(request, authentication.getName());
        return RoomResponse.from(room);
    }

    /*
     * for ngrinder test (join room api)
     */
    @PostMapping("/{roomId}/join")
    @Operation(summary = "채팅방 입장", description = "기존 채팅방에 입장합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "채팅방 입장 성공"),
            @ApiResponse(responseCode = "404", description = "채팅방 없음")
    })
    public void joinRoom(@PathVariable String roomId, @RequestBody JoinRoomRequest request) {
        chatRoomService.userJoin(request);
    }

    @GetMapping
    @Operation(summary = "채팅방 목록 조회", description = "모든 채팅방의 목록을 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "채팅방 목록 조회 성공"),
            @ApiResponse(responseCode = "404", description = "채팅방 목록 조회 실패")
    })
    public List<RoomListResponse> getAllRooms() {
        List<ChatRoom> rooms = chatRoomService.getAllRooms();
        return rooms.stream().map(RoomListResponse::from).toList();
    }

    @GetMapping("/{roomId}")
    @Operation(summary = "채팅방 조회", description = "특정 채팅방의 정보를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "채팅방 조회 성공"),
            @ApiResponse(responseCode = "404", description = "채팅방 조회 실패")
    })
    public ResponseEntity<RoomResponse> getRoom(@PathVariable String roomId) {
        ChatRoom room = chatRoomService.getRoom(roomId);
        if (room != null) {
            return ResponseEntity.ok(RoomResponse.from(room));
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/search")
    @Operation(summary = "채팅방 검색", description = "특정 키워드를 포함하는 채팅방의 목록을 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "채팅방 검색 성공"),
            @ApiResponse(responseCode = "404", description = "채팅방 검색 실패")
    })
    public List<RoomListResponse> searchRooms(@RequestParam String keyword) {
        List<ChatRoom> rooms = chatRoomService.searchRooms(keyword);
        return rooms.stream().map(RoomListResponse::from).toList();
    }
}