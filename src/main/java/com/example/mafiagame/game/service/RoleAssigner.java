package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.domain.Team;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class RoleAssigner {

    public void assignRoles(GameState gameState) {
        if (gameState == null || gameState.getPlayers() == null) {
            return;
        }

        List<GamePlayerState> players = gameState.getPlayers();
        int playerCount = players.size();

        // 역할 분배 로직
        List<PlayerRole> roles = distributeRoles(playerCount);
        Collections.shuffle(roles);

        for (int i = 0; i < playerCount; i++) {
            GamePlayerState player = players.get(i);
            PlayerRole assignedRole = roles.get(i);

            player.setRole(assignedRole);
            player.setTeam(determineTeam(assignedRole));
        }
    }

    private List<PlayerRole> distributeRoles(int playerCount) {
        List<PlayerRole> roles = new ArrayList<>();
        int mafiaCount = Math.max(1, playerCount / 4);

        for (int i = 0; i < mafiaCount; i++) {
            roles.add(PlayerRole.MAFIA);
        }
        roles.add(PlayerRole.DOCTOR);
        roles.add(PlayerRole.POLICE);

        while (roles.size() < playerCount) {
            roles.add(PlayerRole.CITIZEN);
        }
        return roles;
    }

    private Team determineTeam(PlayerRole role) {
        if (role == PlayerRole.MAFIA) {
            return Team.MAFIA;
        } else if (role == PlayerRole.DOCTOR || role == PlayerRole.POLICE || role == PlayerRole.CITIZEN) {
            return Team.CITIZEN;
        } else {
            return Team.NEUTRALITY;
        }
    }
}
