package com.example.mafiagame.game.domain;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GameState {
    private String gamePhase;
    private Instant phaseEndTime;
    private int currentPhase;
}
