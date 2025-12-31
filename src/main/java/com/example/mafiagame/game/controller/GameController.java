package com.example.mafiagame.game.controller;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.user.domain.User;
import com.example.mafiagame.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;
    private final UserService userService;
    private final SimpMessagingTemplate messagingTemplate;

    private Principal getPrincipal(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("user") instanceof Principal) {
            return (Principal) sessionAttributes.get("user");
        }
        return null;
    }

    @PostMapping("/create")
    public ResponseEntity<?> createGame(@RequestBody Map<String, Object> request) {
        String roomId = (String) request.get("roomId");
        List<Map<String, Object>> playersData = (List<Map<String, Object>>) request.get("players");

        List<GamePlayer> players = playersData.stream().map(playerData -> {
            User user = userService.getUserByLoginId((String) playerData.get("playerId"));
            return GamePlayer.builder()
                    .user(user)
                    .isHost((Boolean) playerData.getOrDefault("isHost", false))
                    .isAlive(true)
                    .build();
        }).collect(Collectors.toList());

        Game game = gameService.createGame(roomId, players);
        gameService.assignRoles(game.getGameId()); // 역할 배정 및 메시지 전송
        gameService.startGame(game.getGameId());

        // Redis에서 최신 상태 조회 (클라이언트에게 실시간 정보 전송)
        GameState gameState = gameService.getGameState(game.getGameId());

        messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of("type", "GAME_START", "game", gameState));

        return ResponseEntity.ok(Map.of("success", true, "gameId", game.getGameId()));
    }

    @MessageMapping("/game.vote")
    public void vote(@Payload Map<String, String> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null)
            return;
        gameService.vote(payload.get("gameId"), principal.getName(), payload.get("targetId"));
    }

    @MessageMapping("/game.finalVote")
    public void finalVote(@Payload Map<String, String> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null)
            return;
        gameService.finalVote(payload.get("gameId"), principal.getName(), payload.get("voteChoice"));
    }

    @MessageMapping("/game.nightAction")
    public void nightAction(@Payload Map<String, String> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null)
            return;
        gameService.nightAction(payload.get("gameId"), principal.getName(), payload.get("targetId"));
    }

    @GetMapping("/{gameId}/status")
    public ResponseEntity<?> getGameStatus(@PathVariable String gameId) {
        // 1. 진행 중인 게임이면 Redis에서 상태 조회
        GameState gameState = gameService.getGameState(gameId);
        if (gameState != null) {
            return ResponseEntity.ok(Map.of("success", true, "game", gameState));
        }

        // 2. 종료된 게임이면 DB에서 이력 조회
        Game game = gameService.getGame(gameId);
        return ResponseEntity.ok(Map.of("success", true, "game", game));
    }

    @PostMapping("/update-time")
    public ResponseEntity<?> updateTime(@RequestBody Map<String, Object> payload) {
        String gameId = (String) payload.get("gameId");
        String playerId = (String) payload.get("playerId");
        int seconds = (Integer) payload.get("seconds");

        boolean success = gameService.updateTime(gameId, playerId, seconds);
        if (success) {
            return ResponseEntity.ok(Map.of("success", true, "message", "시간이 조절되었습니다."));
        }
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", "시간 조절에 실패했습니다."));
    }
}