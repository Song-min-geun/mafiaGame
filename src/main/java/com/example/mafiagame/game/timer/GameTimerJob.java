package com.example.mafiagame.game.timer;

import com.example.mafiagame.game.domain.state.GamePhase;

public record GameTimerJob(
        String gameId,
        GamePhase phase,
        int currentPhase,
        String timerToken) {

    private static final String DELIMITER = "|";

    public String toMember() {
        return String.join(
                DELIMITER,
                gameId,
                phase.name(),
                String.valueOf(currentPhase),
                timerToken);
    }

    public static GameTimerJob fromMember(String member) {
        String[] tokens = member.split("\\|", 4);
        if (tokens.length != 4) {
            throw new IllegalArgumentException("Invalid timer member: " + member);
        }

        return new GameTimerJob(
                tokens[0],
                GamePhase.valueOf(tokens[1]),
                Integer.parseInt(tokens[2]),
                tokens[3]);
    }
}
