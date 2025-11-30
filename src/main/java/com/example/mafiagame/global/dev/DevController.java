package com.example.mafiagame.global.dev;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.service.ChatRoomService;
import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/dev")
@RequiredArgsConstructor
@Slf4j
public class DevController {

    private final UserService userService;
    private final ChatRoomService chatRoomService;
    private final GameService gameService;

    @PostMapping("/quick-start")
    public ResponseEntity<?> quickStart(@AuthenticationPrincipal UserDetails userDetails) {
        if (userDetails == null) {
            return ResponseEntity.badRequest().body("로그인이 필요합니다.");
        }

        String hostId = userDetails.getUsername();
        log.info("Dev Quick Start requested by {}", hostId);

        // 1. 더미 유저 생성 (총 8명이 되도록)
        userService.createDummyUsers(8);

        // 2. 방 생성
        ChatRoom room = chatRoomService.createRoom("Dev Quick Start Room", hostId);
        String roomId = room.getRoomId();

        // 3. 더미 유저 입장 및 GamePlayer 리스트 생성
        List<GamePlayer> players = new ArrayList<>();

        // 호스트 추가
        User host = userService.getUserByLoginId(hostId);
        players.add(GamePlayer.builder()
                .user(host)
                .isHost(true)
                .isReady(true)
                .build());

        // 더미 유저 추가 (player1 ~ player8 중 호스트가 아닌 유저들)
        for (int i = 1; i <= 8; i++) {
            String dummyId = "player" + i;
            if (dummyId.equals(hostId))
                continue; // 호스트가 playerN인 경우 중복 방지

            chatRoomService.userJoin(roomId, dummyId);
            User dummy = userService.getUserByLoginId(dummyId);
            players.add(GamePlayer.builder()
                    .user(dummy)
                    .isReady(true)
                    .build());

            if (players.size() >= 8)
                break;
        }

        // 4. 게임 생성 및 시작
        Game game = gameService.createGame(roomId, players);
        gameService.assignRoles(game.getGameId());
        gameService.startGame(game.getGameId());

        return ResponseEntity.ok(Map.of(
                "message", "Quick Start Success",
                "roomId", roomId,
                "gameId", game.getGameId()));
    }
}
