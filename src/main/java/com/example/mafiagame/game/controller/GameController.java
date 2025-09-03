package com.example.mafiagame.game.controller;

import java.security.Principal;
import java.util.HashMap;
import java.util.List;
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
            log.info("🔍 게임 생성 요청 받음: {}", request);
            
            String roomId = (String) request.get("roomId");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> playersData = (List<Map<String, Object>>) request.get("players");
            
            log.info("🔍 방 ID: {}, 플레이어 수: {}", roomId, playersData.size());
            
            // GamePlayer 객체로 변환
            java.util.List<GamePlayer> players = new java.util.ArrayList<>();
            for (Map<String, Object> playerData : playersData) {
                log.info("🔍 플레이어 데이터: {}", playerData);
                
                // ❗ 수정: null 체크 추가
                Boolean isHostValue = (Boolean) playerData.get("isHost");
                boolean isHost = isHostValue != null ? isHostValue : false;
                
                GamePlayer player = GamePlayer.builder()
                        .playerId((String) playerData.get("playerId"))
                        .playerName((String) playerData.get("playerName"))
                        .isHost(isHost)
                        .isAlive(true)
                        .isReady(false)
                        .build();
                players.add(player);
                log.info("🔍 GamePlayer 생성됨: {}", player);
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
     * 투표 처리 (REST API)
     */
    @PostMapping("/vote")
    public ResponseEntity<?> processVote(@RequestBody Map<String, String> request) {
        try {
            String gameId = request.get("gameId");
            String voterId = request.get("voterId");
            String targetId = request.get("targetId");
            
            log.info("🔍 투표 요청: gameId={}, voterId={}, targetId={}", gameId, voterId, targetId);
            
            gameService.vote(gameId, voterId, targetId);
            
            // 투표 완료 메시지를 방에 브로드캐스트
            Game game = gameService.getGame(gameId);
            if (game != null) {
                Map<String, Object> voteMessage = new HashMap<>();
                voteMessage.put("type", "VOTE_COMPLETE");
                voteMessage.put("voterId", voterId);
                voteMessage.put("targetId", targetId);
                
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), voteMessage);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "투표가 완료되었습니다.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("투표 처리 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "투표에 실패했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 밤 액션 처리 (REST API)
     */
    @PostMapping("/night-action")
    public ResponseEntity<?> processNightAction(@RequestBody Map<String, String> request) {
        try {
            String gameId = request.get("gameId");
            String playerId = request.get("playerId");
            String targetId = request.get("targetId");
            
            log.info("🔍 밤 액션 요청: gameId={}, playerId={}, targetId={}", gameId, playerId, targetId);
            
            gameService.processNightAction(gameId, playerId, targetId);
            
            // 밤 액션 완료 메시지를 방에 브로드캐스트
            Game game = gameService.getGame(gameId);
            if (game != null) {
                Map<String, Object> actionMessage = new HashMap<>();
                actionMessage.put("type", "NIGHT_ACTION_COMPLETE");
                actionMessage.put("playerId", playerId);
                actionMessage.put("targetId", targetId);
                
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), actionMessage);
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "밤 액션이 완료되었습니다.");
            
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("밤 액션 처리 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "밤 액션에 실패했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
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
     * 시간 연장/단축
     */
    @PostMapping("/extend-time")
    public ResponseEntity<?> extendTime(@RequestBody Map<String, Object> request) {
        try {
            String gameId = (String) request.get("gameId");
            String playerId = (String) request.get("playerId");
            Integer seconds = (Integer) request.get("seconds");
            
            log.info("🔍 시간 연장/단축 요청: gameId={}, playerId={}, seconds={}", gameId, playerId, seconds);
            
            boolean success = gameService.extendTime(gameId, playerId, seconds);
            
            if (success) {
                // 시간 연장 메시지를 방에 브로드캐스트
                Game game = gameService.getGame(gameId);
                if (game != null) {
                    Map<String, Object> timeMessage = new HashMap<>();
                    timeMessage.put("type", "TIME_EXTENDED");
                    timeMessage.put("playerId", playerId);
                    timeMessage.put("seconds", seconds);
                    timeMessage.put("remainingTime", game.getRemainingTime());
                    
                    messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), timeMessage);
                }
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("message", "시간이 " + seconds + "초 조절되었습니다.");
                response.put("remainingTime", gameService.getGame(gameId).getRemainingTime());
                
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "시간 연장/단축에 실패했습니다.");
                
                return ResponseEntity.badRequest().body(response);
            }
            
        } catch (Exception e) {
            log.error("시간 연장/단축 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "시간 연장/단축에 실패했습니다: " + e.getMessage());
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
