package com.example.mafiagame.game.controller;

import java.security.Principal;
import java.util.ArrayList;
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
import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.service.GameService;
import com.example.mafiagame.game.service.GameTimerService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;
    private final SimpMessagingTemplate messagingTemplate;
    private final GameTimerService gameTimerService;

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
            List<GamePlayer> players = new ArrayList<>();
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
            log.info("의사가 존재하는가? : {}" , hasDoctor);
            boolean hasPolice = (Boolean) request.get("hasPolice");
            log.info("경찰이 존재하는가 ? : {}" , hasPolice);
            
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
            gameStartMessage.put("roomId", game.getRoomId());
            gameStartMessage.put("players", game.getPlayers());
            gameStartMessage.put("status", game.getStatus());
            gameStartMessage.put("currentPhase", game.getCurrentPhase());
            gameStartMessage.put("isDay", game.isDay());
            gameStartMessage.put("dayTimeLimit", game.getDayTimeLimit());
            gameStartMessage.put("nightTimeLimit", game.getNightTimeLimit());
            gameStartMessage.put("remainingTime", game.getRemainingTime());
            
            log.info("🔔 게임 시작 메시지 브로드캐스트: {}", gameStartMessage);
            
            // WebSocket으로 게임 시작 메시지 전송
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), gameStartMessage);
            
            // 시스템 메시지도 함께 전송
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("type", "SYSTEM");
            systemMessage.put("senderId", "SYSTEM");
            systemMessage.put("content", "게임이 시작되었습니다!");
            systemMessage.put("timestamp", java.time.LocalDateTime.now().toString());
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), systemMessage);
            
            // 역할 배정 메시지 전송
            sendRoleAssignmentMessages(game);
            
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
            gameService.processNightResults(gameId);
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
     * 최종 투표 처리 (WebSocket)
     */
    @MessageMapping("/game.vote")
    public void handleVote(@Payload Map<String, Object> payload) {
        try {
            String type = (String) payload.get("type");
            
            if ("FINAL_VOTE".equals(type)) {
                // 최종 투표 처리
                String gameId = (String) payload.get("gameId");
                String playerId = (String) payload.get("playerId");
                String vote = (String) payload.get("vote");
                
                log.info("🔍 최종 투표 요청: gameId={}, playerId={}, vote={}", gameId, playerId, vote);
                
                // 최종 투표 처리
                gameService.processFinalVote(gameId, playerId, vote);
                
            } else {
                // 기존 투표 처리
                String gameId = (String) payload.get("gameId");
                String voterId = (String) payload.get("voterId");
                String targetId = (String) payload.get("targetId");
                
                log.info("🔍 일반 투표 요청: gameId={}, voterId={}, targetId={}", gameId, voterId, targetId);
                
                gameService.vote(gameId, voterId, targetId);
                
                // 투표 완료 메시지 브로드캐스트
                Game game = gameService.getGame(gameId);
                if (game != null) {
                    Map<String, Object> voteMessage = new HashMap<>();
                    voteMessage.put("type", "SYSTEM");
                    voteMessage.put("gameId", gameId);
                    voteMessage.put("voterId", voterId);
                    voteMessage.put("targetId", targetId);
                    
                    messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), voteMessage);
                }
            }
            
        } catch (Exception e) {
            log.error("투표 처리 실패", e);
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
            
            boolean success = gameTimerService.extendTime(gameId, playerId, seconds);
            
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
     * 페이즈 전환
     */
    @PostMapping("/switch-phase")
    public ResponseEntity<?> switchPhase(@RequestBody Map<String, String> request) {
        try {
            String gameId = request.get("gameId");
            Game game = gameService.switchPhase(gameId);
            
            if (game != null) {
                // 페이즈 전환 메시지를 방에 브로드캐스트
                Map<String, Object> phaseMessage = new HashMap<>();
                phaseMessage.put("type", "PHASE_SWITCHED");
                phaseMessage.put("gameId", gameId);
                phaseMessage.put("currentPhase", game.getCurrentPhase());
                phaseMessage.put("isDay", game.isDay());  // ❗ 수정: isDay 필드만 사용
                phaseMessage.put("remainingTime", game.getRemainingTime());
                
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), phaseMessage);
                
                Map<String, Object> response = new HashMap<>();
                response.put("success", true);
                response.put("game", game);
                response.put("message", "페이즈가 전환되었습니다.");
                
                return ResponseEntity.ok(response);
            } else {
                Map<String, Object> response = new HashMap<>();
                response.put("success", false);
                response.put("message", "게임을 찾을 수 없습니다.");
                
                return ResponseEntity.badRequest().body(response);
            }
            
        } catch (Exception e) {
            log.error("페이즈 전환 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "페이즈 전환에 실패했습니다: " + e.getMessage());
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * 투표 결과 처리 (죽은 플레이어 채팅방 추가)
     */
    @PostMapping("/process-vote-results")
    public ResponseEntity<?> processVoteResults(@RequestBody Map<String, String> request) {
        try {
            String gameId = request.get("gameId");
            String eliminatedPlayerId = request.get("eliminatedPlayerId");
            
            if (eliminatedPlayerId != null) {
                Game game = gameService.getGame(gameId);
                if (game != null) {
                    gameService.addDeadPlayerToChatRoom(game.getRoomId(), eliminatedPlayerId);
                    log.info("죽은 플레이어 채팅방에 추가됨: roomId={}, playerId={}", game.getRoomId(), eliminatedPlayerId);
                }
            }
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            log.error("투표 결과 처리 실패", e);
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "투표 결과 처리에 실패했습니다: " + e.getMessage());
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
    
    /**
     * 역할 배정 메시지 전송
     */
    private void sendRoleAssignmentMessages(Game game) {
        try {
            // 각 플레이어에게 개별적으로 역할 전송
            for (GamePlayer player : game.getPlayers()) {
                Map<String, Object> roleMessage = new HashMap<>();
                roleMessage.put("type", "ROLE_ASSIGNED");
                roleMessage.put("gameId", game.getGameId());
                roleMessage.put("roomId", game.getRoomId());
                roleMessage.put("playerId", player.getPlayerId());
                roleMessage.put("playerName", player.getPlayerName());
                roleMessage.put("role", player.getRole().toString());
                roleMessage.put("roleDescription", getRoleDescription(player.getRole()));
                roleMessage.put("timestamp", java.time.LocalDateTime.now().toString());
                
                // 개별 플레이어에게만 전송 (다른 플레이어는 볼 수 없음)
                messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), roleMessage);
            }
            
            // 전체 역할 분포 공개 (역할명만, 누구인지는 비공개)
            Map<String, Object> roleDistributionMessage = new HashMap<>();
            roleDistributionMessage.put("type", "ROLE_DISTRIBUTION");
            roleDistributionMessage.put("gameId", game.getGameId());
            roleDistributionMessage.put("roomId", game.getRoomId());
            roleDistributionMessage.put("roleCounts", getRoleCounts(game));
            roleDistributionMessage.put("timestamp", java.time.LocalDateTime.now().toString());
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), roleDistributionMessage);
            
        } catch (Exception e) {
            log.error("역할 배정 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 역할 설명 반환
     */
    private String getRoleDescription(PlayerRole role) {
        switch (role) {
            case MAFIA:
                return "마피아 - 밤마다 한 명을 선택하여 제거할 수 있습니다.";
            case DOCTOR:
                return "의사 - 밤마다 한 명을 선택하여 마피아의 공격을 막을 수 있습니다.";
            case POLICE:
                return "경찰 - 밤마다 한 명을 선택하여 마피아인지 시민인지 알 수 있습니다.";
            case CITIZEN:
                return "시민 - 낮에 투표로 마피아를 찾아내야 합니다.";
            default:
                return "알 수 없는 역할";
        }
    }
    
    /**
     * 역할별 인원 수 반환
     */
    private Map<String, Integer> getRoleCounts(Game game) {
        Map<String, Integer> roleCounts = new HashMap<>();
        roleCounts.put("MAFIA", 0);
        roleCounts.put("DOCTOR", 0);
        roleCounts.put("POLICE", 0);
        roleCounts.put("CITIZEN", 0);
        
        for (GamePlayer player : game.getPlayers()) {
            String role = player.getRole().toString();
            roleCounts.put(role, roleCounts.get(role) + 1);
        }
        
        return roleCounts;
    }
}
