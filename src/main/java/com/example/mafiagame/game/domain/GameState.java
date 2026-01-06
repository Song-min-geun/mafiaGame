package com.example.mafiagame.game.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public class GameState {

    private String gameId;

    private String roomId;

    @Builder.Default
    private GameStatus status = GameStatus.WAITING;

    @Builder.Default
    private GamePhase gamePhase = GamePhase.DAY_DISCUSSION;

    @Builder.Default
    private int currentPhase = 1;

    private Long phaseEndTime;

    private String winner;

    @Builder.Default
    private List<GamePlayerState> players = new ArrayList<>();

    @Builder.Default
    private List<Vote> votes = new ArrayList<>();

    @Builder.Default
    private List<FinalVote> finalVotes = new ArrayList<>();

    @Builder.Default
    private List<NightAction> nightActions = new ArrayList<>();

    @Builder.Default
    private Map<String, Boolean> votingTimeExtensionsUsed = new HashMap<>();

    private String votedPlayerId;
}