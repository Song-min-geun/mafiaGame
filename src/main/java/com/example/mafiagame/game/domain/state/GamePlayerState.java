package com.example.mafiagame.game.domain.state;

import java.io.Serializable;

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
public class GamePlayerState implements Serializable {
    private static final long serialVersionUID = 1L;

    private String playerId;
    private String playerName;
    private PlayerRole role;
    private Team team;

    @Builder.Default
    private boolean isAlive = true;
}
