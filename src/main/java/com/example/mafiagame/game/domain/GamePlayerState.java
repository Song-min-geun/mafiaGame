package com.example.mafiagame.game.domain;

import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
public class GamePlayerState {
    private String playerId;

    private String playerName;

    private PlayerRole role;

    @Builder.Default
    private boolean isAlive = true;

    @Builder.Default
    private int voteCount = 0;

    @Enumerated(EnumType.STRING)
    private Team team;

    private String targetPlayerId;
}
