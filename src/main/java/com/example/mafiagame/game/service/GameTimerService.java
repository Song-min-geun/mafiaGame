package com.example.mafiagame.game.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GamePlayer;

import lombok.extern.slf4j.Slf4j;

/**
 * 게임 타이머 관리 서비스
 * Java Timer를 사용하여 서버에서 게임 시간을 관리
 */
@Slf4j
@Service
public class GameTimerService {
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    // GameService 대신 직접 게임 데이터 접근
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    
    // 죽은 플레이어들의 채팅방 (roomId -> Set<playerId>)
    private final Map<String, Set<String>> deadPlayersChatRooms = new ConcurrentHashMap<>();
    
    // 게임별 타이머 저장
    private final Map<String, Timer> gameTimers = new ConcurrentHashMap<>();
    
    // 게임별 타이머 태스크 저장
    private final Map<String, TimerTask> gameTimerTasks = new ConcurrentHashMap<>();
    
    /**
     * 게임 등록 (GameService에서 호출)
     */
    public void registerGame(Game game) {
        games.put(game.getGameId(), game);
        log.info("게임 등록됨: {}", game.getGameId());
    }
    
    /**
     * 게임 조회
     */
    public Game getGame(String gameId) {
        return games.get(gameId);
    }
    
    /**
     * 게임 제거
     */
    public void removeGame(String gameId) {
        games.remove(gameId);
        stopGameTimer(gameId);
        log.info("게임 제거됨: {}", gameId);
    }

    @Scheduled(fixedRate = 1000)
    public void updateAllGameTimers() {
        if (games.isEmpty()) {
            return; // 실행 중인 게임이 없으면 아무것도 하지 않음
        }

        // ConcurrentHashMap의 keySet을 사용하여 안전하게 모든 게임 ID를 순회
        for (String gameId : games.keySet()) {
            try {
                updateGameTimer(gameId);
            } catch (Exception e) {
                log.error("게임 타이머 업데이트 중 오류 발생: {}", gameId, e);
            }
        }
    }

    /**
     * 게임 타이머 업데이트 (1초마다 호출)
     */
    private void updateGameTimer(String gameId) {
        Game game = getGame(gameId);
        if (game == null) {
            // 게임이 이미 종료되어 맵에서 제거된 경우일 수 있으므로, 종료.
            return;
        }

        // 게임이 종료되었으면 관리 목록에서 제거하고 타이머 정지
        if (game.getStatus().toString().equals("ENDED")) {
            removeGame(gameId);
            return;
        }

        // 남은 시간 감소
        int remainingTime = game.getRemainingTime();
        if (remainingTime > 0) {
            game.setRemainingTime(remainingTime - 1);

            // 클라이언트에 타이머 업데이트 전송
            sendTimerUpdate(game);

        } else {
            // 시간 종료 - 다음 페이즈로 전환
            log.info("시간 종료! 다음 페이즈로 전환: {}", gameId);
            switchPhase(game);
        }
    }

    public void startGameTimer(String gameId) {
        log.info("게임 {}의 타이머 로직이 중앙 스케줄러에 의해 관리되기 시작합니다.", gameId);
    }

    
    public void stopGameTimer(String gameId) {
        removeGame(gameId);
    }
    
    /**
     * 시간 연장/단축
     */
    public boolean extendTime(String gameId, String playerId, int seconds) {
        Game game = getGame(gameId);
        if (game == null) {
            return false;
        }
        
        // 시간 조절 (최소 0초, 최대 300초)
        int newRemainingTime = Math.max(0, Math.min(300, game.getRemainingTime() + seconds));
        game.setRemainingTime(newRemainingTime);

        // 클라이언트에 시간 연장 메시지 전송
        sendTimeExtendedMessage(game, playerId, seconds);
        
        log.info("시간 {}초 조절됨: {} (플레이어: {}, 남은 시간: {}초)", 
                seconds, gameId, playerId, newRemainingTime);
        
        return true;
    }
    
    /**
     * 게임 플로우 전환 (대화 → 투표 → 반론 → 찬반 → 밤)
     */
    private void switchPhase(Game game) {
        // 현재 페이즈에 따라 다음 페이즈로 전환
        switch (game.getGamePhase()) {
            case DAY_DISCUSSION:
                // 낮 대화 → 투표
                game.setGamePhase(GamePhase.DAY_VOTING);
                game.setRemainingTime(30);  // 투표 30초
                
                // 투표 페이즈별 시간 연장 기회 초기화
                resetVotingTimeExtensions(game);
                
                log.info("낮 대화 → 투표 전환 (30초)");
                break;
                
            case DAY_VOTING:
                // 투표 결과 처리 (최다 득표자 선정만, 아직 제거하지 않음)
                log.info("투표 결과 처리 시작: {}", game.getGameId());
                String votedPlayerId = getVotedPlayerId(game);
                log.info("투표 결과: 최다 득표자 = {}", votedPlayerId);
                
                // 최다 득표자 ID 저장 (최후 변론용)
                game.setVotedPlayerId(votedPlayerId);
                
                // 투표 결과 메시지 전송 (최다 득표자 정보만)
                sendVoteResultUpdate(game, votedPlayerId);
                
                // 최다 득표자 선정 시스템 알림 메시지 전송
                sendVotedPlayerNotification(game, votedPlayerId);
                
                // 투표 → 최후의 반론
                game.setGamePhase(GamePhase.DAY_FINAL_DEFENSE);
                game.setRemainingTime(10);  // 반론 10초
                
                // 투표 페이즈별 시간 연장 기회 초기화
                resetVotingTimeExtensions(game);
                
                log.info("투표 → 최후의 반론 전환 (10초)");
                break;
                
            case DAY_FINAL_DEFENSE:
                // 반론 → 찬성/반대
                game.setGamePhase(GamePhase.DAY_FINAL_VOTE);
                game.setRemainingTime(15);  // 찬반 15초
                log.info("최후의 반론 → 찬성/반대 전환 (15초)");
                break;
                
            case DAY_FINAL_VOTE:
                // 최종 투표 결과 처리
                log.info("최종 투표 결과 처리 시작: {}", game.getGameId());
                String finalVoteResult = processFinalVoteResults(game);
                log.info("최종 투표 결과: {}", finalVoteResult);
                
                // 찬반 → 밤 액션
                game.setGamePhase(GamePhase.NIGHT_ACTION);
                game.setIsDay(false);  // 밤으로 전환
                game.setRemainingTime(30);  // 밤 액션 30초
                log.info("찬성/반대 → 밤 액션 전환 (30초)");
                break;
                
            case NIGHT_ACTION:
                // 밤 → 다음 날 낮 대화
                game.setCurrentPhase(game.getCurrentPhase() + 1);
                game.setGamePhase(GamePhase.DAY_DISCUSSION);
                game.setIsDay(true);  // 낮으로 전환
                game.setRemainingTime(60);  // 낮 대화 60초
                game.getTimeExtensionsUsed();
                log.info("밤 액션 → {}일째 낮 대화 전환 (60초)", game.getCurrentPhase());
                break;
        }
        
        // 페이즈 시작 시간 업데이트
        game.setPhaseStartTime(java.time.LocalDateTime.now());
        
        // 시간 연장 사용 여부 초기화 (새 페이즈마다)
        for (com.example.mafiagame.game.domain.GamePlayer player : game.getPlayers()) {
            game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
        }
    }
    
    /**
     * 클라이언트에 타이머 업데이트 전송
     */
    private void sendTimerUpdate(Game game) {
        try {
            Map<String, Object> message = Map.of(
                "type", "TIMER_UPDATE",
                "gameId", game.getGameId(),
                "roomId", game.getRoomId(),
                "remainingTime", game.getRemainingTime(),
                "gamePhase", game.getGamePhase().toString(),
                "currentPhase", game.getCurrentPhase(),
                "isDay", game.isDay()
            );
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
            
        } catch (Exception e) {
            log.error("타이머 업데이트 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 페이즈 전환 시 시스템 메시지 전송
     */
    private void sendPhaseSwitchMessage(Game game) {
        try {
            // 게임을 다시 조회해서 최신 players 데이터 보장
            Game latestGame = getGame(game.getGameId());
            if (latestGame == null) {
                log.error("게임을 찾을 수 없습니다: {}", game.getGameId());
                return;
            }
            
            String phaseMessage = getPhaseSwitchMessage(latestGame);
            
            log.info("🔍 PHASE_SWITCHED 메시지 전송 - 게임 ID: {}, 플레이어 수: {}", 
                    latestGame.getGameId(), 
                    latestGame.getPlayers() != null ? latestGame.getPlayers().size() : 0);
            
            Map<String, Object> message = new HashMap<>();
            message.put("type", "PHASE_SWITCHED");
            message.put("gameId", latestGame.getGameId());
            message.put("roomId", latestGame.getRoomId());
            message.put("gamePhase", latestGame.getGamePhase().toString());
            message.put("currentPhase", latestGame.getCurrentPhase());
            message.put("isDay", latestGame.isDay());
            message.put("remainingTime", latestGame.getRemainingTime());
            message.put("players", latestGame.getPlayers() != null ? latestGame.getPlayers() : List.of());
            message.put("content", phaseMessage);
            message.put("senderId", "SYSTEM");
            message.put("senderName", "시스템");
            message.put("timestamp", java.time.LocalDateTime.now().toString());
            
            messagingTemplate.convertAndSend("/topic/room." + latestGame.getRoomId(), message);
            
        } catch (Exception e) {
            log.error("페이즈 전환 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 페이즈별 시스템 메시지 생성
     */
    private String getPhaseSwitchMessage(Game game) {
        switch (game.getGamePhase()) {
            case DAY_DISCUSSION:
                return String.format("%d일째 낮 대화가 시작되었습니다. (60초)", game.getCurrentPhase());
            case DAY_VOTING:
                return String.format("%d일째 투표가 시작되었습니다. (30초)", game.getCurrentPhase());
            case DAY_FINAL_DEFENSE:
                return String.format("%d일째 최후의 반론 시간입니다. (10초)", game.getCurrentPhase());
            case DAY_FINAL_VOTE:
                return String.format("%d일째 최종 투표가 시작되었습니다. (30초)", game.getCurrentPhase());
            case NIGHT_ACTION:
                return String.format("%d일째 밤이 되었습니다. 특수 역할이 액션을 수행합니다. (30초)", game.getCurrentPhase());
            default:
                return "페이즈가 전환되었습니다.";
        }
    }
    
    
    /**
     * 투표 페이즈별 시간 연장 기회 초기화
     */
    private void resetVotingTimeExtensions(Game game) {
        if (game.getPlayers() != null) {
            for (GamePlayer player : game.getPlayers()) {
                String key = player.getPlayerId();
                game.getVotingTimeExtensionsUsed().put(key, false);
            }
            log.info("투표 페이즈 {} 시간 연장 기회 초기화 완료", game.getCurrentPhase());
        }
    }
    
    /**
     * 최종 투표 결과 처리 (찬성/반대)
     */
    private String processFinalVoteResults(Game game) {
        Map<String, String> finalVotes = game.getFinalVotes();
        if (finalVotes.isEmpty()) {
            log.info("최종 투표가 없어서 제거된 플레이어 없음");
            return "NO_VOTES";
        }
        
        // 찬성/반대 투표 수 집계
        int agreeCount = 0;
        int disagreeCount = 0;
        
        for (String vote : finalVotes.values()) {
            if ("AGREE".equals(vote)) {
                agreeCount++;
            } else if ("DISAGREE".equals(vote)) {
                disagreeCount++;
            }
        }
        
        log.info("최종 투표 결과: 찬성 {}표, 반대 {}표", agreeCount, disagreeCount);
        
        // 찬성이 과반수면 제거, 아니면 제거하지 않음
        if (agreeCount > disagreeCount) {
            // 찬성이 과반수 - 최다 득표자 제거
            String votedPlayerId = getVotedPlayerId(game);
            if (votedPlayerId != null) {
                GamePlayer eliminated = findPlayer(game, votedPlayerId);
                if (eliminated != null) {
                    eliminated.setIsAlive(false);
                    log.info("최종 투표 결과로 제거됨: {}", eliminated.getPlayerName());
                    
                    // 죽은 플레이어를 죽은 플레이어 채팅방에 추가
                    addDeadPlayerToChatRoom(game.getRoomId(), votedPlayerId);
                    
                    // 최종 투표 결과 메시지 전송
                    sendFinalVoteResultUpdate(game, votedPlayerId, "ELIMINATED");
                    
                    return "ELIMINATED: " + eliminated.getPlayerName();
                }
            }
        } else {
            // 반대가 많거나 동점 - 제거하지 않음
            log.info("최종 투표 결과: 제거하지 않음 (반대가 많거나 동점)");
            
            // 최종 투표 결과 메시지 전송
            sendFinalVoteResultUpdate(game, null, "NOT_ELIMINATED");
            
            return "NOT_ELIMINATED";
        }
        
        return "NO_ACTION";
    }
    
    /**
     * 투표 결과 처리 (최다 득표자 선정만, 제거하지 않음)
     */
    private String processVoteResults(Game game) {
        Map<String, String> votes = game.getVotes();
        if (votes.isEmpty()) {
            log.info("투표가 없어서 최다 득표자 없음");
            return null;
        }
        
        // 투표 수 집계
        Map<String, Integer> voteCounts = new HashMap<>();
        for (String targetId : votes.values()) {
            voteCounts.put(targetId, voteCounts.getOrDefault(targetId, 0) + 1);
        }
        
        // 가장 많은 투표를 받은 플레이어 찾기
        String votedPlayerId = null;
        int maxVotes = 0;
        
        for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                votedPlayerId = entry.getKey();
            }
        }
        
        // 동점인 경우 무작위 선택
        if (votedPlayerId != null) {
            List<String> tiedPlayers = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
                if (entry.getValue() == maxVotes) {
                    tiedPlayers.add(entry.getKey());
                }
            }
            
            if (tiedPlayers.size() > 1) {
                votedPlayerId = tiedPlayers.get(new Random().nextInt(tiedPlayers.size()));
            }
        }
        
        // 투표 초기화 (최종 투표를 위해)
        game.getVotes().clear();
        
        log.info("최다 득표자 선정: {} ({}표)", votedPlayerId, maxVotes);
        return votedPlayerId;
    }
    
    /**
     * 최다 득표자 찾기
     */
    private String getVotedPlayerId(Game game) {
        Map<String, String> votes = game.getVotes();
        if (votes.isEmpty()) {
            return null;
        }
        
        // 투표 수 집계
        Map<String, Integer> voteCounts = new HashMap<>();
        for (String targetId : votes.values()) {
            voteCounts.put(targetId, voteCounts.getOrDefault(targetId, 0) + 1);
        }
        
        // 가장 많은 투표를 받은 플레이어 찾기
        String votedPlayerId = null;
        int maxVotes = 0;
        
        for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                votedPlayerId = entry.getKey();
            }
        }
        
        // 동점인 경우 무작위 선택
        if (votedPlayerId != null) {
            List<String> tiedPlayers = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
                if (entry.getValue() == maxVotes) {
                    tiedPlayers.add(entry.getKey());
                }
            }
            
            if (tiedPlayers.size() > 1) {
                votedPlayerId = tiedPlayers.get(new Random().nextInt(tiedPlayers.size()));
            }
        }
        
        return votedPlayerId;
    }
    
    /**
     * 플레이어 찾기
     */
    private GamePlayer findPlayer(Game game, String playerId) {
        if (game.getPlayers() == null) return null;
        
        return game.getPlayers().stream()
                .filter(player -> player.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 생존 플레이어 목록 조회
     */
    private List<GamePlayer> getAlivePlayers(Game game) {
        if (game.getPlayers() == null) return new ArrayList<>();
        
        return game.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .collect(Collectors.toList());
    }
    
    
    /**
     * 죽은 플레이어를 죽은 플레이어 채팅방에 추가
     */
    public void addDeadPlayerToChatRoom(String roomId, String playerId) {
        deadPlayersChatRooms.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        log.info("죽은 플레이어 채팅방에 추가: roomId={}, playerId={}", roomId, playerId);
    }
    
    /**
     * 죽은 플레이어 채팅방에 있는 플레이어들 조회
     */
    public Set<String> getDeadPlayersInChatRoom(String roomId) {
        return deadPlayersChatRooms.getOrDefault(roomId, Collections.emptySet());
    }
    
    /**
     * 플레이어가 죽은 플레이어 채팅방에 있는지 확인
     */
    public boolean isPlayerInDeadChatRoom(String roomId, String playerId) {
        return deadPlayersChatRooms.getOrDefault(roomId, Collections.emptySet()).contains(playerId);
    }
    
    /**
     * 죽은 플레이어 채팅방 초기화
     */
    public void clearDeadPlayersChatRoom(String roomId) {
        deadPlayersChatRooms.remove(roomId);
        log.info("죽은 플레이어 채팅방 초기화: roomId={}", roomId);
    }
    
    /**
     * 최종 투표 결과 업데이트 메시지 전송
     */
    private void sendFinalVoteResultUpdate(Game game, String eliminatedPlayerId, String result) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "FINAL_VOTE_RESULT_UPDATE");
            message.put("gameId", game.getGameId());
            message.put("roomId", game.getRoomId());
            message.put("players", game.getPlayers());
            message.put("eliminatedPlayerId", eliminatedPlayerId);
            message.put("result", result); // "ELIMINATED" or "NOT_ELIMINATED"
            message.put("eliminatedPlayerName", eliminatedPlayerId != null ? 
                game.getPlayers().stream()
                    .filter(p -> p.getPlayerId().equals(eliminatedPlayerId))
                    .map(p -> p.getPlayerName())
                    .findFirst()
                    .orElse("알 수 없는 플레이어") : null);
            
            // WebSocket으로 메시지 전송
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
            
            log.info("최종 투표 결과 업데이트 메시지 전송 완료: result={}, eliminatedPlayerId={}", result, eliminatedPlayerId);
            
        } catch (Exception e) {
            log.error("최종 투표 결과 업데이트 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 투표 결과 업데이트 메시지 전송
     */
    private void sendVoteResultUpdate(Game game, String eliminatedPlayerId) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("type", "VOTE_RESULT_UPDATE");
            message.put("gameId", game.getGameId());
            message.put("roomId", game.getRoomId());
            message.put("players", game.getPlayers());
            message.put("eliminatedPlayerId", eliminatedPlayerId);
            message.put("eliminatedPlayerName", eliminatedPlayerId != null ? 
                game.getPlayers().stream()
                    .filter(p -> p.getPlayerId().equals(eliminatedPlayerId))
                    .map(p -> p.getPlayerName())
                    .findFirst()
                    .orElse("알 수 없는 플레이어") : null);
            
            // WebSocket으로 메시지 전송
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
            
            log.info("투표 결과 업데이트 메시지 전송 완료: {}", eliminatedPlayerId);
            
        } catch (Exception e) {
            log.error("투표 결과 업데이트 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 최다 득표자 선정 시스템 알림 메시지 전송
     */
    private void sendVotedPlayerNotification(Game game, String votedPlayerId) {
        try {
            if (votedPlayerId == null) {
                log.info("최다 득표자가 없어서 알림 메시지 전송하지 않음");
                return;
            }
            
            // 최다 득표자 이름 찾기
            String votedPlayerName = game.getPlayers().stream()
                    .filter(p -> p.getPlayerId().equals(votedPlayerId))
                    .map(p -> p.getPlayerName())
                    .findFirst()
                    .orElse("알 수 없는 플레이어");
            
            // 시스템 알림 메시지 생성
            Map<String, Object> systemMessage = new HashMap<>();
            systemMessage.put("type", "SYSTEM");
            systemMessage.put("senderId", "SYSTEM");
            systemMessage.put("senderName", "시스템");
            systemMessage.put("roomId", game.getRoomId());
            systemMessage.put("content", String.format("🗳️ %s님이 최다 득표를 받았습니다. 최후의 변론 시간입니다.", votedPlayerName));
            systemMessage.put("timestamp", java.time.LocalDateTime.now().toString());
            
            // WebSocket으로 시스템 메시지 전송
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), systemMessage);
            
            log.info("최다 득표자 선정 시스템 알림 메시지 전송 완료: {} ({})", votedPlayerName, votedPlayerId);
            
        } catch (Exception e) {
            log.error("최다 득표자 선정 시스템 알림 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 시간 연장 메시지 전송
     */
    private void sendTimeExtendedMessage(Game game, String playerId, int seconds) {
        try {
            // 플레이어 닉네임 찾기
            String playerName = findPlayerControllerTimer(game,playerId);
            
            // 시간 증가/감소에 따라 메시지 타입 결정
            String messageType = isExtendTime(seconds);
            
            Map<String, Object> message = new HashMap<>();
            message.put("type", messageType);
            message.put("gameId", game.getGameId());
            message.put("roomId", game.getRoomId());
            message.put("playerId", playerId);
            message.put("playerName", playerName);
            message.put("seconds", Math.abs(seconds)); // 절댓값으로 전송
            message.put("remainingTime", game.getRemainingTime());
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
            
        } catch (Exception e) {
            log.error("시간 연장 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }

    public String findPlayerControllerTimer(Game game, String playerId){
        return game.getPlayers().stream()
                .filter(player ->player.getPlayerId().equals(playerId))
                .map(GamePlayer::getPlayerName)
                .findFirst()
                .orElse("등록되지 않은 사용자입니다.");
    }

    public String isExtendTime(int seconds){
        return seconds > 0 ? "TIME_EXTEND" : "TIME_REDUCE";
    }
    
    /**
     * 모든 게임 타이머 정지 (서버 종료 시)
     */
    public void stopAllTimers() {
        log.info("모든 게임 타이머 정지 중...");
        gameTimers.keySet().forEach(this::stopGameTimer);
        log.info("모든 게임 타이머 정지 완료");
    }
}
