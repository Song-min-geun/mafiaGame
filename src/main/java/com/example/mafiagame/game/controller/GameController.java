package com.example.mafiagame.game.controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.service.GameService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
@Slf4j
public class GameController {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 게임 생성
     */
    @PostMapping("/create")
    public ResponseEntity<?> createGame(@RequestBody Map<String, Object> request) {
        try {
            String roomId = (String) request.get("roomId");
            @SuppressWarnings("unchecked")
            Map<String, Object>[] playersData = (Map<String, Object>[]) request.get("players");
            
            // GamePlayer 객체로 변환
            java.util.List<GamePlayer> players = new java.util.ArrayList<>();
            for (Map<String, Object> playerData : playersData) {
                GamePlayer player = GamePlayer.builder()
                        .playerId((String) playerData.get("playerId"))
                        .playerName((String) playerData.get("playerName"))
                        .isHost((Boolean) playerData.get("isHost"))
                        .isAlive(true)
                        .isReady(false)
                        .build();
                players.add(player);
            }
            
            int maxPlayers = (Integer) request.get("maxPlayers");
            boolean hasDoctor = (Boolean) request.get("hasDoctor");
            boolean hasPolice = (Boolean) request.get("hasPolice");
            
            Game game = gameService.createGame(roomId, players, maxPlayers, hasDoctor, hasPolice);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("gameId", game.getGameId());
            response.put("message", "게임이 생성되었습니다.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("게임 생성 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "게임 생성에 실패했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 게임 시작
     */
    @PostMapping("/start")
    public ResponseEntity<?> startGame(@RequestBody Map<String, String> request) {
        try {
            String gameId = request.get("gameId");
            Game game = gameService.startGame(gameId);
            
            // 게임 시작 메시지를 방에 브로드캐스트
            Map<String, Object> gameStartMessage = new HashMap<>();
            gameStartMessage.put("type", "GAME_START");
            gameStartMessage.put("gameId", gameId);
            gameStartMessage.put("players", game.getPlayers());
            gameStartMessage.put("status", game.getStatus());
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), gameStartMessage);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "게임이 시작되었습니다.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("게임 시작 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "게임 시작에 실패했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 밤 액션 처리 (WebSocket)
     */
    @MessageMapping("/game.nightAction")
    public void processNightAction(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null) {
            log.error("Principal이 null입니다.");
            return;
        }
        
        String gameId = (String) payload.get("gameId");
        String playerId = principal.getName();
        String targetId = (String) payload.get("targetId");
        
        try {
            gameService.processNightAction(gameId, playerId, targetId);
            
            // 액션 완료 메시지 전송
            Map<String, Object> actionMessage = new HashMap<>();
            actionMessage.put("type", "NIGHT_ACTION_COMPLETE");
            actionMessage.put("playerId", playerId);
            
            Game game = gameService.getGame(gameId);
            if (game != null) {
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), actionMessage);
            }
            
        } catch (Exception e) {
            log.error("밤 액션 처리 실패", e);
        }
    }

    /**
     * 투표 처리 (WebSocket)
     */
    @MessageMapping("/game.vote")
    public void processVote(@Payload Map<String, Object> payload, Principal principal) {
        if (principal == null) {
            log.error("Principal이 null입니다.");
            return;
        }
        
        String gameId = (String) payload.get("gameId");
        String voterId = principal.getName();
        String targetId = (String) payload.get("targetId");
        
        try {
            gameService.vote(gameId, voterId, targetId);
            
            // 투표 완료 메시지 전송
            Map<String, Object> voteMessage = new HashMap<>();
            voteMessage.put("type", "VOTE_COMPLETE");
            voteMessage.put("voterId", voterId);
            
            Game game = gameService.getGame(gameId);
            if (game != null) {
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), voteMessage);
            }
            
        } catch (Exception e) {
            log.error("투표 처리 실패", e);
        }
    }

    /**
     * 게임 상태 조회
     */
    @GetMapping("/{gameId}")
    public ResponseEntity<?> getGameStatus(@PathVariable String gameId) {
        try {
            Game game = gameService.getGame(gameId);
            if (game == null) {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "게임을 찾을 수 없습니다.");
                return ResponseEntity.notFound().build();
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("game", game);
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("게임 상태 조회 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "게임 상태 조회에 실패했습니다.");
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 게임 종료
     */
    @PostMapping("/end")
    public ResponseEntity<?> endGame(@RequestBody Map<String, String> request) {
        try {
            String gameId = request.get("gameId");
            Game game = gameService.getGame(gameId);
            
            if (game != null) {
                gameService.deleteGame(gameId);
                
                // 게임 종료 메시지를 방에 브로드캐스트
                Map<String, Object> gameEndMessage = new HashMap<>();
                gameEndMessage.put("type", "GAME_END");
                gameEndMessage.put("gameId", gameId);
                gameEndMessage.put("message", "게임이 종료되었습니다.");
                
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), gameEndMessage);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "게임이 종료되었습니다.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("게임 종료 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "게임 종료에 실패했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }
}
