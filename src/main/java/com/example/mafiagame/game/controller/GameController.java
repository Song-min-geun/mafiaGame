package com.example.mafiagame.game.controller;

import com.example.mafiagame.game.domain.entity.Game;
import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.PlayerRole;

import com.example.mafiagame.game.dto.response.CreateGameResponse;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.game.service.SuggestionService;
import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.chat.service.ChatRoomService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@Tag(name = "GameController", description = "게임 관리 API")
@RestController
@Slf4j
@RequestMapping("/api/games")
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;
    private final SuggestionService suggestionService;
    private final ChatRoomService chatRoomService;

    private Principal getPrincipal(SimpMessageHeaderAccessor accessor) {
        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes != null && sessionAttributes.get("user") instanceof Principal) {
            return (Principal) sessionAttributes.get("user");
        }
        return null;
    }

    @PostMapping("/create")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "게임 생성", description = "새로운 게임을 생성합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "게임 생성 성공"),
            @ApiResponse(responseCode = "400", description = "게임 생성 실패")
    })
    public ResponseEntity<CreateGameResponse> createGame(Principal principal) {
        if (principal == null) {
            return ResponseEntity.badRequest().body(CreateGameResponse.fail("인증 정보가 없습니다."));
        }

        String userId = principal.getName();

        // 유저가 현재 참여 중인 ChatRoom 조회
        ChatRoom chatRoom = chatRoomService.findRoomByUserId(userId);
        if (chatRoom == null) {
            return ResponseEntity.badRequest().body(CreateGameResponse.fail("참여 중인 채팅방이 없습니다."));
        }

        // 방장만 게임 시작 가능
        if (!chatRoom.getHostId().equals(userId)) {
            return ResponseEntity.badRequest().body(CreateGameResponse.fail("방장만 게임을 시작할 수 있습니다."));
        }

        GameState gameState = gameService.createGame(chatRoom.getRoomId());
        return ResponseEntity.ok(CreateGameResponse.success(gameState.getGameId(), gameState.getRoomId()));
    }

    @MessageMapping("/game.vote")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 투표", description = "게임 투표를 합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 투표 성공"),
            @ApiResponse(responseCode = "400", description = "게임 투표 실패")
    })
    public void vote(@Payload Map<String, String> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null)
            return;
        gameService.vote(payload.get("gameId"), principal.getName(), payload.get("targetId"));
    }

    @MessageMapping("/game.finalVote")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 최종 투표", description = "게임 최종 투표를 합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 최종 투표 성공"),
            @ApiResponse(responseCode = "400", description = "게임 최종 투표 실패")
    })
    public void finalVote(@Payload Map<String, String> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null)
            return;
        gameService.finalVote(payload.get("gameId"), principal.getName(), payload.get("voteChoice"));
    }

    @MessageMapping("/game.nightAction")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 밤 행동", description = "게임 밤 행동을 합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 밤 행동 성공"),
            @ApiResponse(responseCode = "400", description = "게임 밤 행동 실패")
    })
    public void nightAction(@Payload Map<String, String> payload, SimpMessageHeaderAccessor accessor) {
        Principal principal = getPrincipal(accessor);
        if (principal == null)
            return;
        gameService.nightAction(payload.get("gameId"), principal.getName(), payload.get("targetId"));
    }

    @GetMapping("/{gameId}/status")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 상태 조회", description = "게임 상태를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 상태 조회 성공"),
            @ApiResponse(responseCode = "400", description = "게임 상태 조회 실패")
    })
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
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 시간 조절", description = "게임 시간을 조절합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 시간 조절 성공"),
            @ApiResponse(responseCode = "400", description = "게임 시간 조절 실패")
    })
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
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 상태 조회", description = "게임 상태를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 상태 조회 성공"),
            @ApiResponse(responseCode = "400", description = "게임 상태 조회 실패")
    })
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
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 상태 조회", description = "게임 상태를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 상태 조회 성공"),
            @ApiResponse(responseCode = "400", description = "게임 상태 조회 실패")
    })
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
                    "roomId", gameState.getRoomId()));
        }

        return ResponseEntity.ok(Map.of("success", false, "message", "참여 중인 게임이 없습니다."));
    }

    /**
     * 역할과 페이즈에 따른 채팅 추천 문구 조회
     * GET /api/game/suggestions?role=MAFIA&phase=NIGHT_ACTION
     */
    @GetMapping("/suggestions")
    @ResponseStatus(HttpStatus.OK)
    @Operation(summary = "게임 상태 조회", description = "게임 상태를 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "게임 상태 조회 성공"),
            @ApiResponse(responseCode = "400", description = "게임 상태 조회 실패")
    })
    public ResponseEntity<?> getSuggestions(
            @RequestParam("role") PlayerRole role,
            @RequestParam("phase") GamePhase phase,
            @RequestParam(value = "gameId", required = false) String gameId) {
        try {
            log.info("getSuggestions 요청: role={}, phase={}, gameId={}", role, phase, gameId);
            List<String> suggestions = suggestionService.getSuggestions(role, phase, gameId);
            log.info("getSuggestions 결과: 개수={}", suggestions != null ? suggestions.size() : 0);

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