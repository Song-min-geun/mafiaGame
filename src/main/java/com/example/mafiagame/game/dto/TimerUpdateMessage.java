package com.example.mafiagame.game.dto;

import com.example.mafiagame.game.domain.GameState;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerUpdateMessage {
    private final String type = "TIMER_UPDATE";
    private String gameId;
    private String roomId;
    private String phaseEndTime; // 변경: remainingTime -> phaseEndTime (String)
    private String gamePhase;
    private int currentPhase;
    // private boolean isDay; // 삭제됨

    public void updateFrom(GameState gameState) {
        this.gameId = gameState.getGameId();
        this.roomId = gameState.getRoomId();
        this.phaseEndTime = gameState.getPhaseEndTime() != null ? gameState.getPhaseEndTime().toString() : null;
        this.gamePhase = gameState.getGamePhase().toString();
        this.currentPhase = gameState.getCurrentPhase();
    }
}
