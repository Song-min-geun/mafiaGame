package com.example.mafiagame.game.timer;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameStatus;

public record GameTimerMeta(
        GamePhase phase,
        int currentPhase,
        GameStatus status,
        Long phaseEndTime,
        String timerToken) {
}
