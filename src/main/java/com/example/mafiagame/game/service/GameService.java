package com.example.mafiagame.game.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.example.mafiagame.game.domain.Game;
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
                .isNight(false)
                .nightCount(0)
                .dayCount(0)
                .votes(new HashMap<>())
                .nightActions(new HashMap<>())
                .maxPlayers(maxPlayers)
                .hasDoctor(hasDoctor)
                .hasPolice(hasPolice)
                .build();
        
        games.put(gameId, game);
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
        game.setIsNight(true);
        game.setNightCount(1);
        
        // ❗ 추가: 시간 초기화
        game.setPhaseStartTime(LocalDateTime.now());
        game.setRemainingTime(game.getNightTimeLimit());
        
        // ❗ 추가: 플레이어별 시간 연장 사용 여부 초기화
        for (GamePlayer player : game.getPlayers()) {
            game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
        }
        
        log.info("게임 시작됨: {} (밤 시간: {}초)", gameId, game.getNightTimeLimit());
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
        if (game == null) return;
        
        GamePlayer player = findPlayer(game, playerId);
        if (player == null || !player.isAlive()) return;
        
        // 밤 액션 저장
        game.getNightActions().put(playerId, targetId);
        
        log.info("밤 액션 저장: {} -> {}", playerId, targetId);
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
                } else {
                    log.info("의사가 치료함: {}", target.getPlayerName());
                }
            }
        }
        
        // 밤 액션 초기화
        game.getNightActions().clear();
        
        // 낮으로 전환
        game.setIsNight(false);
        game.setDayCount(game.getDayCount() + 1);
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
        log.info("투표: {} -> {}", voterId, targetId);
    }
    
    /**
     * 투표 결과 처리
     */
    public String processVoteResults(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;
        
        Map<String, String> votes = game.getVotes();
        List<GamePlayer> players = game.getPlayers();
        
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
            }
        }
        
        // 투표 초기화
        game.getVotes().clear();
        
        // 밤으로 전환
        game.setIsNight(true);
        game.setNightCount(game.getNightCount() + 1);
        game.setCurrentPhase(game.getCurrentPhase() + 1);
        
        return eliminatedPlayerId;
    }
    
    /**
     * 게임 종료 조건 확인
     */
    public String checkGameEnd(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return null;
        
        List<GamePlayer> players = game.getPlayers();
        int aliveMafia = 0;
        int aliveCitizens = 0;
        
        for (GamePlayer player : players) {
            if (!player.isAlive()) continue;
            
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
     * 시간 연장/단축
     */
    public boolean extendTime(String gameId, String playerId, int seconds) {
        Game game = games.get(gameId);
        if (game == null) {
            log.error("게임을 찾을 수 없습니다: {}", gameId);
            return false;
        }
        
        // 플레이어가 이미 시간 연장을 사용했는지 확인
        if (game.getTimeExtensionsUsed().getOrDefault(playerId, false)) {
            log.warn("플레이어 {}는 이미 시간 연장을 사용했습니다.", playerId);
            return false;
        }
        
        // ±15초 제한
        if (Math.abs(seconds) > 15) {
            log.warn("시간 연장/단축은 ±15초로 제한됩니다: {}", seconds);
            return false;
        }
        
        // 시간 연장/단축 적용
        int newRemainingTime = game.getRemainingTime() + seconds;
        
        // 최소 5초, 최대 120초 제한
        newRemainingTime = Math.max(5, Math.min(120, newRemainingTime));
        
        game.setRemainingTime(newRemainingTime);
        game.getTimeExtensionsUsed().put(playerId, true);
        
        log.info("시간 연장/단축: 플레이어 {}가 {}초 조절 (남은 시간: {}초)", 
                playerId, seconds, newRemainingTime);
        
        return true;
    }
    
    /**
     * 페이즈 전환 (밤 -> 낮, 낮 -> 밤)
     */
    public void switchPhase(String gameId) {
        Game game = games.get(gameId);
        if (game == null) return;
        
        if (game.isNight()) {
            // 밤 -> 낮
            game.setIsNight(false);
            game.setDayCount(game.getDayCount() + 1);
            game.setRemainingTime(game.getDayTimeLimit());
            log.info("밤 -> 낮 전환 (낮 시간: {}초)", game.getDayTimeLimit());
        } else {
            // 낮 -> 밤
            game.setIsNight(true);
            game.setNightCount(game.getNightCount() + 1);
            game.setRemainingTime(game.getNightTimeLimit());
            log.info("낮 -> 밤 전환 (밤 시간: {}초)", game.getNightTimeLimit());
        }
        
        // 페이즈 시작 시간 업데이트
        game.setPhaseStartTime(LocalDateTime.now());
        
        // 시간 연장 사용 여부 초기화 (새 페이즈마다)
        for (GamePlayer player : game.getPlayers()) {
            game.getTimeExtensionsUsed().put(player.getPlayerId(), false);
        }
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
