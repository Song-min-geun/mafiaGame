package com.example.mafiagame.game.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameState {

    private String gameId;

    private String roomId;

    private String roomName;

    @Builder.Default
    private GameStatus status = GameStatus.WAITING;

    @Builder.Default
    private GamePhase gamePhase = GamePhase.NIGHT_ACTION;

    @Builder.Default
    private int currentPhase = 1;

    private Long phaseEndTime;

    private String winner;

    @Builder.Default
    private List<GamePlayerState> players = new ArrayList<>();

    @Builder.Default
    private List<Vote> votes = new ArrayList<>();

    @Builder.Default
    private List<FinalVote> finalVotes = new ArrayList<>();

    @Builder.Default
    private List<NightAction> nightActions = new ArrayList<>();

    @Builder.Default
    private Map<String, Boolean> votingTimeExtensionsUsed = new HashMap<>();

    private String votedPlayerId;

    /**
     * 특정 플레이어가 살아있는지 확인
     */
    public boolean isPlayerAlive(String playerId) {
        return players.stream()
                .anyMatch(p -> p.getPlayerId().equals(playerId) && p.isAlive());
    }

    /**
     * 특정 플레이어 조회
     */
    public GamePlayerState findPlayer(String playerId) {
        return players.stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 플레이어가 현재 채팅 가능한지 확인
     */
    public boolean canPlayerChat(String playerId) {
        GamePlayerState player = findPlayer(playerId);

        // 플레이어가 없거나 죽었으면 채팅 불가
        if (player == null || !player.isAlive()) {
            return false;
        }

        // 최후 변론 단계: 투표된 플레이어만 발언 가능
        if (gamePhase == GamePhase.DAY_FINAL_DEFENSE) {
            return playerId.equals(votedPlayerId);
        }

        // 밤 페이즈: 마피아만 대화 가능
        if (gamePhase == GamePhase.NIGHT_ACTION) {
            return player.getRole() == PlayerRole.MAFIA;
        }

        return true;
    }

    /**
     * 플레이어가 방을 떠날 수 있는지 확인
     * (게임 진행 중 살아있는 플레이어는 퇴장 불가)
     */
    public boolean canPlayerLeave(String playerId) {
        return !isPlayerAlive(playerId);
    }

    /**
     * 살아있는 특정 플레이어 조회
     */
    public GamePlayerState findActivePlayer(String playerId) {
        GamePlayerState player = findPlayer(playerId);
        return (player != null && player.isAlive()) ? player : null;
    }

    /**
     * 생존한 마피아 수
     */
    public long countAliveMafia() {
        return players.stream()
                .filter(p -> p.isAlive() && p.getRole() == PlayerRole.MAFIA)
                .count();
    }

    /**
     * 생존한 시민(마피아 제외) 수
     */
    public long countAliveCitizen() {
        return players.stream()
                .filter(p -> p.isAlive() && p.getRole() != PlayerRole.MAFIA)
                .count();
    }

    /**
     * 게임 종료 조건 확인 및 승자 반환
     * 
     * @return "MAFIA", "CITIZEN", 또는 null (게임 계속)
     */
    public String checkWinner() {
        long mafia = countAliveMafia();
        long citizen = countAliveCitizen();

        if (mafia >= citizen) {
            return "MAFIA";
        }
        if (mafia == 0) {
            return "CITIZEN";
        }
        return null; // 게임 계속
    }

    /**
     * 투표에서 최다 득표자 ID 목록 반환
     */
    public List<String> getTopVotedPlayerIds() {
        if (votes == null || votes.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Long> counts = votes.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Vote::getTargetId,
                        java.util.stream.Collectors.counting()));

        if (counts.isEmpty()) {
            return new ArrayList<>();
        }

        long max = java.util.Collections.max(counts.values());
        return counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
    }
}