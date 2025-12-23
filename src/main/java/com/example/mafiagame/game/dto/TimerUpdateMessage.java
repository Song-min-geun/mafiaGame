package com.example.mafiagame.game.dto;

import com.example.mafiagame.game.domain.Game;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TimerUpdateMessage {
    private final String type = "TIMER_UPDATE";
    private String gameId;
    private String roomId;
    private int remainingTime;
    private String gamePhase;
    private int currentPhase;
    private boolean isDay;

    public void updateFrom(Game game) {
        this.gameId = game.getGameId();
        this.roomId = game.getRoomId();
        this.remainingTime = game.getRemainingTime();
        this.gamePhase = game.getGamePhase().toString();
        this.currentPhase = game.getCurrentPhase();
        this.isDay = game.isDay();
    }
}
