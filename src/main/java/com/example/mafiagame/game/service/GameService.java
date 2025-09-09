package com.example.mafiagame.game.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.example.mafiagame.game.domain.Game;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GamePlayer;
import com.example.mafiagame.game.domain.GameStatus;
import com.example.mafiagame.game.domain.PlayerRole;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameService {
    
    private final Map<String, Game> games = new ConcurrentHashMap<>();
    
    // 죽은 플레이어들의 채팅방 (roomId -> Set<playerId>)
    private final Map<String, Set<String>> deadPlayersChatRooms = new ConcurrentHashMap<>();
    
    @Autowired
    private GameTimerService gameTimerService;
    
    @Autowired
    private SimpMessagingTemplate messagingTemplate;
    
    
    /**
     * 게임 생성
     */
    public Game createGame(String roomId, List<GamePlayer> players, int maxPlayers, boolean hasDoctor, boolean hasPolice) {
        String gameId = "game_" + System.currentTimeMillis() + "_" + new Random().nextInt(1000);
        
        Game game = Game.builder()
                .gameId(gameId)
                .roomId(roomId)
                .status(GameStatus.WAITING)
                .players(new ArrayList<>(players))
                .currentPhase(0)
                .isDay(true)
                .votes(new HashMap<>())
                .nightActions(new HashMap<>())
                .maxPlayers(maxPlayers)
                .hasDoctor(hasDoctor)
                .hasPolice(hasPolice)
                .build();
        
        games.put(gameId, game);
        
        // GameTimerService에 게임 등록
        gameTimerService.registerGame(game);
        
        log.info("게임 생성됨: {}", gameId);
        return game;
    }
    
    /**
     * 게임 시작
     */
    public Game startGame(String gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            throw new RuntimeException("게임을 찾을 수 없습니다: " + gameId);
        }
        
        if (game.getPlayers().size() < 4) {
            throw new RuntimeException("최소 4명의 플레이어가 필요합니다.");
        }
        
        // 역할 배정
        assignRoles(game);
        
        // 게임 상태 변경
        game.setStatus(GameStatus.STARTING);
        game.setStartTime(LocalDateTime.now());
        game.setCurrentPhase(1);
        game.setIsDay(true);  // 낮으로 시작
        game.setGamePhase(GamePhase.DAY_DISCUSSION);  // 1일째 낮 대화로 시작
        
        // 시간 초기화
        game.setPhaseStartTime(LocalDateTime.now());
        game.setRemainingTime(60);  // 낮 대화 60초
        
        // 플레이어별 시간 연장 사용 여부 초기화
        for (GamePlayer player : game.getPlayers()) {
            game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
        }
        
        // GameTimerService에 게임 등록 및 타이머 시작
        gameTimerService.registerGame(game);
        gameTimerService.startGameTimer(gameId);
        
        log.info("게임 시작됨: {} (낮 대화: {}초)", gameId, game.getRemainingTime());
        return game;
    }
    
    /**
     * 역할 배정
     */
    private void assignRoles(Game game) {
        List<GamePlayer> players = game.getPlayers();
        int playerCount = players.size();
        
        // 마피아 수 계산 (플레이어 수의 1/3, 최소 1명)
        int mafiaCount = Math.max(1, playerCount / 3);
        
        // 역할 목록 생성
        List<PlayerRole> roles = new ArrayList<>();
        
        // 마피아 추가
        for (int i = 0; i < mafiaCount; i++) {
            roles.add(PlayerRole.MAFIA);
        }
        
        // 특수 역할 추가
        if (game.isHasDoctor()) {
            roles.add(PlayerRole.DOCTOR);
        }

        if (game.isHasPolice()) {
            roles.add(PlayerRole.POLICE);
        }
        
        // 나머지는 시민
        int citizenCount = playerCount - roles.size();
        for (int i = 0; i < citizenCount; i++) {
            roles.add(PlayerRole.CITIZEN);
        }
        
        // 역할 섞기
        Collections.shuffle(roles);
        
        // 플레이어에게 역할 배정
        for (int i = 0; i < players.size(); i++) {
            players.get(i).setRole(roles.get(i));
            players.get(i).setIsAlive(true);
            players.get(i).setIsReady(false);
            players.get(i).setVoteCount(0);
        }
    }
    
    /**
     * 밤 액션 처리
     */
    public void processNightAction(String gameId, String playerId, String targetId) {
        Game game = games.get(gameId);
        if (game == null) {
            throw new IllegalArgumentException("게임을 찾을 수 없습니다: " + gameId);
        }
        
        GamePlayer player = findPlayer(game, playerId);
        if (player == null || !player.isAlive()) {
            throw new IllegalArgumentException("플레이어가 존재하지 않거나 생존하지 않습니다: " + playerId);
        }
        
        // 특수 역할만 밤 액션 가능
        if (player.getRole() == PlayerRole.CITIZEN) {
            throw new IllegalArgumentException("시민은 밤 액션을 할 수 없습니다: " + player.getPlayerName());
        }
        
        // 밤 액션 저장
        game.getNightActions().put(playerId, targetId);
        
        // 개별 액션 결과 메시지 전송
        sendNightActionResult(game, player, targetId);
        
        // 경찰인 경우 조사 결과 즉시 전송
        if (player.getRole() == PlayerRole.POLICE) {
            GamePlayer target = findPlayer(game, targetId);
            if (target != null && target.isAlive()) {
                sendPoliceInvestigationResult(game, player.getPlayerId(), target.getRole());
            }
        }
        
        log.info("밤 액션 저장: {} ({}) -> {}", player.getPlayerName(), player.getRole(), targetId);
    }
    
    /**
     * 밤 결과 처리
     */
    public void processNightResults(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return;
        
        Map<String, String> nightActions = game.getNightActions();
        List<GamePlayer> players = game.getPlayers();
        
        // 마피아의 타겟
        String mafiaTarget = null;
        // 의사의 타겟
        String doctorTarget = null;
        // 경찰의 타겟
        String policeTarget = null;
        
        // 각 역할별 액션 수집
        for (GamePlayer player : players) {
            if (!player.isAlive()) continue;
            
            String targetId = nightActions.get(player.getPlayerId());
            if (targetId == null) continue;
            
            switch (player.getRole()) {
                case MAFIA:
                    mafiaTarget = targetId;
                    break;
                case DOCTOR:
                    doctorTarget = targetId;
                    break;
                case POLICE:
                    policeTarget = targetId;
                    break;
                case CITIZEN:
                    // 시민은 밤 액션이 없음
                    break;
            }
        }
        
        // 마피아 타겟 처리
        if (mafiaTarget != null) {
            GamePlayer target = findPlayer(game, mafiaTarget);
            if (target != null && target.isAlive()) {
                // 의사가 치료하지 않았다면 사망
                if (!mafiaTarget.equals(doctorTarget)) {
                    target.setIsAlive(false);
                    log.info("플레이어 사망: {}", target.getPlayerName());
                    
                    // 죽은 플레이어를 죽은 플레이어 채팅방에 추가
                    gameTimerService.addDeadPlayerToChatRoom(game.getRoomId(), mafiaTarget);
                } else {
                    log.info("의사가 치료함: {}", target.getPlayerName());
                }
            }
        }
        
        // 경찰 조사 결과는 이미 processNightAction에서 처리됨
        
        // 밤 결과 메시지 전송
        sendNightResultMessage(game, mafiaTarget, doctorTarget);
        
        // 게임 종료 조건 확인
        String winner = checkGameEnd(gameId);
        if (winner != null) {
            endGame(gameId, winner);
            return;
        }
        
        // 밤 액션 초기화
        game.getNightActions().clear();
        
        // 낮으로 전환
        game.setIsDay(true);
    }
    
    /**
     * 개별 밤 액션 결과 메시지 전송
     */
    private void sendNightActionResult(Game game, GamePlayer player, String targetId) {
        try {
            GamePlayer target = findPlayer(game, targetId);
            if (target == null) return;
            
            Map<String, Object> actionMessage = new HashMap<>();
            actionMessage.put("type", "SYSTEM");
            actionMessage.put("gameId", game.getGameId());
            actionMessage.put("roomId", game.getRoomId());
            actionMessage.put("playerId", player.getPlayerId());
            actionMessage.put("targetName", target.getPlayerName());
            actionMessage.put("senderId", "SYSTEM");
            actionMessage.put("senderName", "시스템");
            actionMessage.put("timestamp", java.time.LocalDateTime.now().toString());
            
            switch (player.getRole()) {
                case MAFIA:
                    actionMessage.put("content", String.format("%s님이 선택되었습니다.", target.getPlayerName()));
                    break;
                case DOCTOR:
                    actionMessage.put("content", String.format("%s님이 선택되었습니다.", target.getPlayerName()));
                    break;
                case POLICE:
                    actionMessage.put("content", String.format("%s님이 선택되었습니다.", target.getPlayerName()));
                    break;
            }
            
            // 해당 플레이어에게만 개인 메시지 전송
            messagingTemplate.convertAndSendToUser(player.getPlayerId(), "/queue/night-action", actionMessage);
            
            log.info("밤 액션 결과 메시지 전송: {} -> {}", player.getPlayerName(), target.getPlayerName());
            
        } catch (Exception e) {
            log.error("밤 액션 결과 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 밤 결과 메시지 전송
     */
    private void sendNightResultMessage(Game game, String mafiaTarget, String doctorTarget) {
        try {
            String resultMessage;
            
            if (mafiaTarget != null) {
                GamePlayer target = findPlayer(game, mafiaTarget);
                if (target != null && target.isAlive()) {
                    // 의사가 치료했는지 확인
                    if (mafiaTarget.equals(doctorTarget)) {
                        resultMessage = "이번 밤에 살해는 일어나지 않았습니다.";
                    } else {
                        resultMessage = String.format("%s님이 살해되었습니다.", target.getPlayerName());
                    }
                } else {
                    resultMessage = "이번 밤에 살해는 일어나지 않았습니다.";
                }
            } else {
                resultMessage = "이번 밤에 살해는 일어나지 않았습니다.";
            }
            
            // 밤 결과 시스템 메시지 전송
            Map<String, Object> nightResultMessage = new HashMap<>();
            nightResultMessage.put("type", "SYSTEM");
            nightResultMessage.put("senderId", "SYSTEM");
            nightResultMessage.put("senderName", "시스템");
            nightResultMessage.put("roomId", game.getRoomId());
            nightResultMessage.put("content", resultMessage);
            nightResultMessage.put("timestamp", java.time.LocalDateTime.now().toString());
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), nightResultMessage);
            
            log.info("밤 결과 메시지 전송: {}", resultMessage);
            
        } catch (Exception e) {
            log.error("밤 결과 메시지 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 경찰 조사 결과 전송
     */
    private void sendPoliceInvestigationResult(Game game, String policePlayerId, PlayerRole targetRole) {
        try {
            // 경찰에게만 전송할 개인 메시지
            Map<String, Object> investigationMessage = new HashMap<>();
            investigationMessage.put("type", "SYSTEM");
            investigationMessage.put("gameId", game.getGameId());
            investigationMessage.put("roomId", game.getRoomId());
            investigationMessage.put("targetRole", targetRole.name());
            investigationMessage.put("isMafia", targetRole == PlayerRole.MAFIA);
            investigationMessage.put("senderId", "SYSTEM");
            investigationMessage.put("senderName", "시스템");
            investigationMessage.put("timestamp", java.time.LocalDateTime.now().toString());
            
            // 경찰에게만 전송 (개인 메시지)
            messagingTemplate.convertAndSendToUser(policePlayerId, "/queue/police", investigationMessage);
            
            log.info("경찰 조사 결과 전송: {} -> {}", policePlayerId, targetRole);
            
        } catch (Exception e) {
            log.error("경찰 조사 결과 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 투표 처리
     */
    public void vote(String gameId, String voterId, String targetId) {
        Game game = games.get(gameId);
        if (game == null) return;
        
        GamePlayer voter = findPlayer(game, voterId);
        if (voter == null || !voter.isAlive()) return;
        
        game.getVotes().put(voterId, targetId);

        GamePlayer target = findPlayer(game, targetId);
        target.setVoteCount(target.getVoteCount() + 1);

    }

    // 최다 득표자 변별 후 채팅가능하게 만들기
    public void processVote(String gameId){
        Game game = games.get(gameId);
        if (game == null) return;
        
        // 투표 결과 처리
        String votedPlayerId = getVotedPlayerId(gameId);
        if (votedPlayerId != null) {
            log.info("투표 결과 플레이어: {} - 최종 변론 권한 부여", votedPlayerId);
        }
    }
    
    /**
     * 최종 투표 처리 (찬성/반대)
     */
    public void processFinalVote(String gameId, String playerId, String vote) {
        Game game = games.get(gameId);
        if (game == null) {
            throw new IllegalArgumentException("게임을 찾을 수 없습니다: " + gameId);
        }
        
        // 투표자가 생존자인지 확인
        GamePlayer voter = findPlayer(game, playerId);
        if (voter == null || !voter.isAlive()) {
            throw new IllegalArgumentException("투표자가 존재하지 않거나 생존하지 않습니다: " + playerId);
        }
        
        // 최다 득표자(변론자)는 자신에게 투표할 수 없음
        String votedPlayerId = getVotedPlayerId(gameId);
        if (votedPlayerId != null && votedPlayerId.equals(playerId)) {
            throw new IllegalArgumentException("최다 득표자는 자신에게 투표할 수 없습니다: " + voter.getPlayerName());
        }
        
        // 최종 투표 기록 (찬성/반대)
        game.getFinalVotes().put(playerId, vote);
        log.info("최종 투표 기록: {} -> {}", voter.getPlayerName(), vote);
    }
    
    /**
     * 생존 플레이어 목록 조회
     */
    public List<GamePlayer> getAlivePlayers(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return new ArrayList<>();
        
        return game.getPlayers().stream()
                .filter(GamePlayer::isAlive)
                .collect(Collectors.toList());
    }
    
    /**
     * 투표 결과로 선택된 플레이어 ID 조회
     */
    public String getVotedPlayerId(String gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            log.info("🔍 getVotedPlayerId: 게임이 없음. gameId={}", gameId);
            return null;
        }
        
        // 저장된 votedPlayerId가 있으면 반환 (최후 변론용)
        if (game.getVotedPlayerId() != null) {
            log.info("🔍 getVotedPlayerId: 저장된 votedPlayerId 반환. gameId={}, votedPlayerId={}", 
                    gameId, game.getVotedPlayerId());
            return game.getVotedPlayerId();
        }
        
        // 투표가 없으면 null 반환
        Map<String, String> votes = game.getVotes();
        if (votes.isEmpty()) {
            log.info("🔍 getVotedPlayerId: 투표가 없음. gameId={}", gameId);
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
        
        log.info("🔍 getVotedPlayerId: 투표 결과 플레이어: {} ({}표). gameId={}", votedPlayerId, maxVotes, gameId);
        return votedPlayerId;
    }
    
    /**
     * 투표 결과 처리
     */
    public String processVoteResults(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;
        
        Map<String, String> votes = game.getVotes();
        
        // 투표 수 집계
        Map<String, Integer> voteCounts = new HashMap<>();
        for (String targetId : votes.values()) {
            voteCounts.put(targetId, voteCounts.getOrDefault(targetId, 0) + 1);
        }
        
        // 가장 많은 투표를 받은 플레이어 찾기
        String eliminatedPlayerId = null;
        int maxVotes = 0;
        
        for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                eliminatedPlayerId = entry.getKey();
            }
        }
        
        // 동점인 경우 무작위 선택
        if (eliminatedPlayerId != null) {
            List<String> tiedPlayers = new ArrayList<>();
            for (Map.Entry<String, Integer> entry : voteCounts.entrySet()) {
                if (entry.getValue() == maxVotes) {
                    tiedPlayers.add(entry.getKey());
                }
            }
            
            if (tiedPlayers.size() > 1) {
                eliminatedPlayerId = tiedPlayers.get(new Random().nextInt(tiedPlayers.size()));
            }
        }
        
        // 플레이어 제거
        if (eliminatedPlayerId != null) {
            GamePlayer eliminated = findPlayer(game, eliminatedPlayerId);
            if (eliminated != null) {
                eliminated.setIsAlive(false);
                log.info("투표로 제거됨: {}", eliminated.getPlayerName());
                
                // 죽은 플레이어를 죽은 플레이어 채팅방에 추가
                addDeadPlayerToChatRoom(game.getRoomId(), eliminatedPlayerId);
            }
        }
        
        // 게임 종료 조건 확인
        String winner = checkGameEnd(gameId);
        if (winner != null) {
            endGame(gameId, winner);
            return eliminatedPlayerId;
        }
        
        // 투표 초기화
        game.getVotes().clear();
        
        // 밤으로 전환
        game.setIsDay(false);
        game.setCurrentPhase(game.getCurrentPhase() + 1);
        
        // 클라이언트에 투표 결과 업데이트 전송
        sendVoteResultUpdate(game, eliminatedPlayerId);
        
        return eliminatedPlayerId;
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
     * 게임 종료 시 죽은 플레이어 채팅방 정리
     */
    public void clearDeadPlayersChatRoom(String roomId) {
        deadPlayersChatRooms.remove(roomId);
        log.info("죽은 플레이어 채팅방 정리: roomId={}", roomId);
    }
    
    /**
     * roomId로 게임 조회
     */
    public Game getGameByRoomId(String roomId) {
        Game game = games.values().stream()
                .filter(g -> g.getRoomId().equals(roomId))
                .findFirst()
                .orElse(null);
        
        log.info("🔍 getGameByRoomId: roomId={}, found={}, gamePhase={}, votedPlayerId={}", 
                roomId, game != null, game != null ? game.getGamePhase() : "null", 
                game != null ? game.getVotedPlayerId() : "null");
        
        return game;
    }
    
    /**
     * 투표 결과 업데이트 전송
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
            
            messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), message);
            
            log.info("투표 결과 업데이트 전송: {}", eliminatedPlayerId);
            
        } catch (Exception e) {
            log.error("투표 결과 업데이트 전송 실패: {}", game.getGameId(), e);
        }
    }
    
    /**
     * 게임 종료 처리
     */
    public void endGame(String gameId, String winner) {
        Game game = games.get(gameId);
        if (game == null) return;
        
        // 게임 상태를 종료로 설정
        game.setStatus(GameStatus.ENDED);
        game.setWinner(winner);
        game.setEndTime(LocalDateTime.now());
        
        // 타이머 정지
        gameTimerService.stopGameTimer(gameId);
        
        // 승리자 메시지 전송
        String winnerMessage = winner.equals("MAFIA") ? "마피아의 승리!" : "시민의 승리!";
        Map<String, Object> gameEndMessage = new HashMap<>();
        gameEndMessage.put("type", "GAME_ENDED");
        gameEndMessage.put("gameId", gameId);
        gameEndMessage.put("roomId", game.getRoomId());
        gameEndMessage.put("winner", winner);
        gameEndMessage.put("message", winnerMessage);
        gameEndMessage.put("timestamp", LocalDateTime.now().toString());
        
        messagingTemplate.convertAndSend("/topic/room." + game.getRoomId(), gameEndMessage);
        
        log.info("게임 종료: {} - 승리자: {}", gameId, winner);
    }
    
    /**
     * 게임 종료 조건 확인
     */
    public String checkGameEnd(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;
        
        List<GamePlayer> alivePlayers = getAlivePlayers(gameId);
        int aliveMafia = 0;
        int aliveCitizens = 0;
        
        for (GamePlayer player : alivePlayers) {
            if (player.getRole() == PlayerRole.MAFIA) {
                aliveMafia++;
            } else {
                aliveCitizens++;
            }
        }
        
        // 마피아 승리 조건: 마피아 수 >= 시민 수
        if (aliveMafia >= aliveCitizens && aliveCitizens > 0) {
            game.setStatus(GameStatus.ENDED);
            game.setWinner("MAFIA");
            game.setEndTime(LocalDateTime.now());
            return "MAFIA";
        }
        
        // 시민 승리 조건: 마피아 수 = 0
        if (aliveMafia == 0) {
            game.setStatus(GameStatus.ENDED);
            game.setWinner("CITIZEN");
            game.setEndTime(LocalDateTime.now());
            return "CITIZEN";
        }
        
        return null; // 게임 계속
    }
    
    /**
     * 플레이어 찾기
     */
    private GamePlayer findPlayer(Game game, String playerId) {
        return game.getPlayers().stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * 게임 조회
     */
    public Game getGame(String gameId) {
        return games.get(gameId);
    }
    
    /**
     * 시간 연장/단축 (GameTimerService로 위임)
     * 순환 참조 해결을 위해 GameController에서 직접 GameTimerService 호출
     */
    public boolean extendTime(String gameId, String playerId, int seconds) {
        // GameTimerService에서 직접 처리하도록 변경
        return false; // 이 메서드는 더 이상 사용되지 않음
    }
    
    /**
     * 게임 플로우 전환 (대화 → 투표 → 반론 → 찬반 → 밤)
     */
    public Game switchPhase(String gameId) {
        Game game = games.get(gameId);
        if (game == null) {
            log.error("게임을 찾을 수 없습니다: {}", gameId);
            return null;
        }
        
        // 현재 페이즈에 따라 다음 페이즈로 전환
        switch (game.getGamePhase()) {
            case DAY_DISCUSSION:
                // 낮 대화 → 투표
                game.setGamePhase(GamePhase.DAY_VOTING);
                game.setRemainingTime(30);  // 투표 30초
                log.info("낮 대화 → 투표 전환 (30초)");
                break;
                
            case DAY_VOTING:
                // 투표 → 최후의 반론
                game.setGamePhase(GamePhase.DAY_FINAL_DEFENSE);
                game.setRemainingTime(10);  // 반론 10초
                log.info("투표 → 최후의 반론 전환 (10초)");
                break;
                
            case DAY_FINAL_DEFENSE:
                // 반론 → 찬성/반대
                game.setGamePhase(GamePhase.DAY_FINAL_VOTE);
                game.setRemainingTime(15);  // 찬반 15초
                log.info("최후의 반론 → 찬성/반대 전환 (15초)");
                break;
                
            case DAY_FINAL_VOTE:
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
                log.info("밤 액션 → {}일째 낮 대화 전환 (60초)", game.getCurrentPhase());
                break;
        }
        
        // 페이즈 시작 시간 업데이트
        game.setPhaseStartTime(LocalDateTime.now());
        
        // 시간 연장 사용 여부 초기화 (새 페이즈마다)
        for (GamePlayer player : game.getPlayers()) {
            game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
        }
        
        return game;
    }
    
    /**
     * 남은 시간 업데이트
     */
    public int updateRemainingTime(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return 0;
        
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime phaseStart = game.getPhaseStartTime();
        
        if (phaseStart == null) {
            game.setPhaseStartTime(now);
            return game.getRemainingTime();
        }
        
        // 경과 시간 계산 (초)
        long elapsedSeconds = java.time.Duration.between(phaseStart, now).getSeconds();
        int remaining = (int) (game.getRemainingTime() - elapsedSeconds);
        
        // 시간이 다 되면 0으로 설정
        if (remaining <= 0) {
            remaining = 0;
        }
        
        game.setRemainingTime(remaining);
        return remaining;
    }

    /**
     * 게임 삭제
     */
    public void deleteGame(String gameId) {
        games.remove(gameId);
        log.info("게임 삭제됨: {}", gameId);
    }
}
