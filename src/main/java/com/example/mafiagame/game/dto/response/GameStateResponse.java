package com.example.mafiagame.game.dto.response;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.domain.state.PlayerRole;
import com.example.mafiagame.game.domain.state.Team;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Optional;

public record GameStateResponse(
        String gameId,
        String roomId,
        String roomName,
        GameStatus status,
        GamePhase gamePhase,
        int currentPhase,
        Long phaseEndTime,
        String votedPlayerId,
        List<Player> players) {

    public static GameStateResponse from(GameState gameState, String viewerId) {
        return new GameStateResponse(
                gameState.getGameId(),
                gameState.getRoomId(),
                gameState.getRoomName(),
                gameState.getStatus(),
                gameState.getGamePhase(),
                gameState.getCurrentPhase(),
                gameState.getPhaseEndTime(),
                gameState.getVotedPlayerId(),
                Optional.ofNullable(gameState.getPlayers()).orElseGet(List::of).stream()
                        .map(player -> Player.from(player, viewerId))
                        .toList());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Player(
            String playerId,
            String playerName,
            boolean alive,
            boolean me,
            PlayerRole role,
            Team team) {

        private static Player from(GamePlayerState player, String viewerId) {
            boolean isMe = viewerId.equals(player.getPlayerId());
            return new Player(
                    player.getPlayerId(),
                    player.getPlayerName(),
                    player.isAlive(),
                    isMe,
                    isMe ? player.getRole() : null,
                    isMe ? player.getTeam() : null);
        }
    }
}
