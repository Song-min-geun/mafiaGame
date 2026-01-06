package com.example.mafiagame.game.domain;

import com.example.mafiagame.user.domain.User;
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
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private PlayerRole role;

    @Builder.Default
    private boolean isAlive = true;

    public String getPlayerName() {
        return this.user != null ? this.user.getNickname() : "Unknown";
    }

    public String getPlayerId() {
        return this.user != null ? this.user.getUserLoginId() : null;
    }

}
