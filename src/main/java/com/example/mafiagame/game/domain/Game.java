package com.example.mafiagame.game.domain;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "games")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Game {
    @Id
    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GameStatus status;

    @Column(name = "winner")
    private String winner;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Builder.Default
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<GamePlayer> players = new ArrayList<>();

    // --- 삭제된 필드들 (GameState로 이동됨) ---
    // isDay, currentPhase, gamePhase, votes, finalVotes, nightActions,
    // votedPlayerId, votingTimeExtensionsUsed, remainingTime, phaseEndTime
    // 모두 삭제됨

    // playerMap은 편의상 유지
    @Transient
    @JsonIgnore
    @Builder.Default
    private Map<String, GamePlayer> playerMap = new HashMap<>();

    public int getDayTimeLimit() {
        return 60; // 기본값 60초
    }

    public int getNightTimeLimit() {
        return 30; // 기본값 30초
    }

    public void buildPlayerMap() {
        if (playerMap == null) {
            playerMap = new HashMap<>();
        }
        playerMap.clear();
        if (players != null) {
            for (GamePlayer player : players) {
                String playerId = player.getPlayerId();
                if (playerId != null) {
                    playerMap.put(playerId, player);
                }
            }
        }
    }

    public GamePlayer getPlayerById(String playerId) {
        if (playerMap == null || playerMap.isEmpty()) {
            buildPlayerMap();
        }
        return playerMap.get(playerId);
    }
}
