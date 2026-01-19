package com.example.mafiagame.game.state;

import com.example.mafiagame.game.domain.GamePhase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 페이즈별 상태를 제공하는 Factory
 */
@Component
@RequiredArgsConstructor
public class GamePhaseFactory {

    private final DayDiscussionState dayDiscussionState;
    private final DayVotingState dayVotingState;
    private final DayFinalDefenseState dayFinalDefenseState;
    private final DayFinalVotingState dayFinalVotingState;
    private final NightActionState nightActionState;

    /**
     * 현재 페이즈에 맞는 상태 객체 반환
     */
    public GamePhaseState getState(GamePhase phase) {
        return switch (phase) {
            case DAY_DISCUSSION -> dayDiscussionState;
            case DAY_VOTING -> dayVotingState;
            case DAY_FINAL_DEFENSE -> dayFinalDefenseState;
            case DAY_FINAL_VOTING -> dayFinalVotingState;
            case NIGHT_ACTION -> nightActionState;
        };
    }
}
