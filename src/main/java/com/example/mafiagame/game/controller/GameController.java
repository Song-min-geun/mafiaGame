package com.example.mafiagame.game.controller;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.dto.request.CreateGameRequest;
import com.example.mafiagame.game.dto.request.SuggestionsRequestDto;
import com.example.mafiagame.game.service.GameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    private Principal getPrincipal(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("user") instanceof Principal) {
            return (Principal) sessionAttributes.get("user");
        }
        return null;
    }

    @PostMapping("/create")
    public ResponseEntity<?> createGame(@RequestBody CreateGameRequest request) {
        GameState gameState = gameService.createGameStartGame(request);
        return ResponseEntity.ok(Map.of("success", true, "gameId", gameState.getGameId()));
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

    /**
     * 방 ID로 현재 진행 중인 게임 상태 조회
     * 새로고침 시 게임 상태 복구용
     */
    @GetMapping("/state/{roomId}")
    public ResponseEntity<?> getGameStateByRoom(@PathVariable String roomId) {
        Game game = gameService.getGameByRoomId(roomId);
        if (game == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "진행 중인 게임이 없습니다."));
        }

        GameState gameState = gameService.getGameState(game.getGameId());
        if (gameState != null) {
            return ResponseEntity.ok(Map.of("success", true, "data", gameState));
        }

        return ResponseEntity.ok(Map.of("success", false, "message", "게임 상태를 찾을 수 없습니다."));
    }

    /**
     * 현재 유저가 참여 중인 게임 조회
     * 새로고침 시 자동 재연결용
     */
    @GetMapping("/my-game")
    public ResponseEntity<?> getMyGame(Principal principal) {
        if (principal == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "인증 정보가 없습니다."));
        }

        String userId = principal.getName();
        GameState gameState = gameService.getGameByPlayerId(userId);

        if (gameState != null) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", gameState,
                    "roomId", gameState.getRoomId(),
                    "roomName", gameState.getRoomName() != null ? gameState.getRoomName() : ""));
        }

        return ResponseEntity.ok(Map.of("success", false, "message", "참여 중인 게임이 없습니다."));
    }

    /**
     * 역할과 페이즈에 따른 채팅 추천 문구 조회
     */
    @GetMapping("/suggestions")
    public ResponseEntity<?> getSuggestions(
            @RequestParam SuggestionsRequestDto dto) {
        try {
            PlayerRole playerRole = dto.role();
            GamePhase gamePhase = dto.phase();

            List<String> suggestions = gameService.getSuggestions(playerRole, gamePhase);

            if (suggestions == null || suggestions.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "data", List.of(),
                        "message", "해당 역할/페이즈에 등록된 추천 문구가 없습니다."));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", suggestions));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "잘못된 역할 또는 페이즈입니다: " + e.getMessage()));
        }
    }
}