package com.example.mafiagame.game.domain;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.stream.Collectors;

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
public class GameState implements Serializable {
    private static final long serialVersionUID = 1L;

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
    private String votedPlayerId;

    @Builder.Default
    private List<GamePlayerState> players = new ArrayList<>();

    // 투표: voterId → targetId
    @Builder.Default
    private Map<String, String> votes = new HashMap<>();

    // 최종 투표: voterId → "AGREE" or "DISAGREE"
    @Builder.Default
    private Map<String, String> finalVotes = new HashMap<>();

    // 밤 행동: actorId → targetId
    @Builder.Default
    private Map<String, String> nightActions = new HashMap<>();

    @Builder.Default
    private Map<String, Boolean> votingTimeExtensionsUsed = new HashMap<>();

    // ================== 헬퍼 메서드 ================== //

    public boolean isPlayerAlive(String playerId) {
        return players.stream()
                .anyMatch(p -> p.getPlayerId().equals(playerId) && p.isAlive());
    }

    public GamePlayerState findPlayer(String playerId) {
        return players.stream()
                .filter(p -> p.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    public boolean canPlayerChat(String playerId) {
        GamePlayerState player = findPlayer(playerId);

        if (player == null || !player.isAlive()) {
            return false;
        }

        if (gamePhase == GamePhase.DAY_FINAL_DEFENSE) {
            return playerId.equals(votedPlayerId);
        }

        if (gamePhase == GamePhase.NIGHT_ACTION) {
            return player.getRole() == PlayerRole.MAFIA;
        }

        return true;
    }

    public boolean canPlayerLeave(String playerId) {
        return !isPlayerAlive(playerId);
    }

    public GamePlayerState findActivePlayer(String playerId) {
        GamePlayerState player = findPlayer(playerId);
        return (player != null && player.isAlive()) ? player : null;
    }

    public long countAliveMafia() {
        return players.stream()
                .filter(p -> p.isAlive() && p.getRole() == PlayerRole.MAFIA)
                .count();
    }

    public long countAliveCitizen() {
        return players.stream()
                .filter(p -> p.isAlive() && p.getRole() != PlayerRole.MAFIA)
                .count();
    }

    public String checkWinner() {
        long mafia = countAliveMafia();
        long citizen = countAliveCitizen();

        if (mafia >= citizen) {
            return "MAFIA";
        }
        if (mafia == 0) {
            return "CITIZEN";
        }
        return null;
    }

    public List<String> getTopVotedPlayerIds() {
        if (votes == null || votes.isEmpty()) {
            return new ArrayList<>();
        }

        Map<String, Long> counts = votes.values().stream()
                .collect(Collectors.groupingBy(targetId -> targetId, Collectors.counting()));

        if (counts.isEmpty()) {
            return new ArrayList<>();
        }

        long max = Collections.max(counts.values());
        return counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
    }
}