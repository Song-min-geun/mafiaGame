package com.example.mafiagame.game.strategy;

import com.example.mafiagame.game.domain.PlayerRole;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 역할별 전략을 제공하는 Factory
 */
@Component
@RequiredArgsConstructor
public class RoleActionFactory {

    private final MafiaAction mafiaAction;
    private final DoctorAction doctorAction;
    private final PoliceAction policeAction;

    /**
     * 역할에 맞는 전략 반환
     */
    public RoleActionStrategy getStrategy(PlayerRole role) {
        return switch (role) {
            case MAFIA -> mafiaAction;
            case DOCTOR -> doctorAction;
            case POLICE -> policeAction;
            case CITIZEN -> null; // 시민은 밤 행동 없음
        };
    }

    /**
     * 해당 역할이 밤 행동을 할 수 있는지 확인
     */
    public boolean canActAtNight(PlayerRole role) {
        return role != PlayerRole.CITIZEN;
    }
}
