package com.example.mafiagame.game.domain;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "game_id")
    @JsonBackReference
    private Game game;

    private String playerId;

    private String playerName;

    private PlayerRole role;

    @Builder.Default
    @JsonProperty("isAlive")
    private boolean isAlive = true;

    @Builder.Default
    @JsonProperty("isHost")
    private boolean isHost = false;

    @Builder.Default
    @JsonProperty("isReady")
    private boolean isReady = false;

    @Builder.Default
    private int voteCount = 0;

    private String targetPlayerId;

    public void setIsAlive(boolean isAlive) {
        this.isAlive = isAlive;
    }
    
    public void setIsReady(boolean isReady) {
        this.isReady = isReady;
    }
    
    public void setRole(PlayerRole role) {
        this.role = role;
    }
    
    public void setVoteCount(int voteCount) {
        this.voteCount = voteCount;
    }

    public void setAlive(boolean b) {
        this.isAlive = b;
    }

    public void setGame(Game game) {
        this.game = game;
    }

    // 추가 필요한 메서드들
    public String getPlayerId() {
        return playerId;
    }

    public String getPlayerName() {
        return playerName;
    }

    public PlayerRole getRole() {
        return role;
    }

    public boolean isAlive() {
        return isAlive;
    }

    public boolean isHost() {
        return isHost;
    }

    public boolean isReady() {
        return isReady;
    }

    public int getVoteCount() {
        return voteCount;
    }

    public String getTargetPlayerId() {
        return targetPlayerId;
    }

    public void setPlayerId(String playerId) {
        this.playerId = playerId;
    }

    public void setPlayerName(String playerName) {
        this.playerName = playerName;
    }

    public void setTargetPlayerId(String targetPlayerId) {
        this.targetPlayerId = targetPlayerId;
    }
}
