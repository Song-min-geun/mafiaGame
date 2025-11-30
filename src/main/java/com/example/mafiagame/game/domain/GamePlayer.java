package com.example.mafiagame.game.domain;

import com.example.mafiagame.user.domain.User;
import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @com.fasterxml.jackson.annotation.JsonIgnore
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

    public String getPlayerName() {
        return this.user != null ? this.user.getNickname() : "Unknown";
    }

    public String getPlayerId() {
        return this.user != null ? this.user.getUserLoginId() : null;
    }

}
