package com.example.mafiagame.game.domain;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class Players {

    final private List<GamePlayer> players;

    /**
     * 플레이어 목록 전체를 반환합니다.
     */
    public List<GamePlayer> getAsList(){
        return Collections.unmodifiableList(players); //외부에서 수정 불가
    }

    /**
     * ID로 특정 플레이어를 찾습니다.
     */
    public GamePlayer findById(String playerId) {
        return players.stream()
                .filter(player -> player.getPlayerId().equals(playerId))
                .findFirst()
                .orElse(null);
    }

    /**
     * 살아있는 모든 플레이어를 반환합니다.
     */
    public List<GamePlayer> findAllAlivePlayers() {
        return players.stream()
                .filter(GamePlayer::isAlive)
                .collect(Collectors.toList());
    }

    /**
     * 살아있는 마피아와 시민의 수를 계산합니다.
     * @return Map<String, Long> 형태로 {"Mafia": 수, "CITIZEN": 수} 를 반환
     */
    public Map<String,Long> countAliveRoles(){
        return players.stream()
                .filter(GamePlayer::isAlive)
                .collect(Collectors.groupingBy(
                        player -> player.getRole() == PlayerRole.MAFIA ? "MAFIA" : "CITIZEN",
                        Collectors.counting()
                ));
    }

    /**
     * 모든  플레이어에게 역할을 무작위로 배정합니다.
     */
    public void assignRoles(int mafiaCount, boolean hasDoctor, boolean hasPolice){
        int playerCount = players.size();
        List<PlayerRole> roles = new ArrayList<>();

        for(int i = 0; i < mafiaCount; i++){
            roles.add(PlayerRole.MAFIA);
        }

        if (hasDoctor) {
            roles.add(PlayerRole.DOCTOR);
        }

        if (hasPolice) {
            roles.add(PlayerRole.POLICE);
        }

        while(roles.size() < playerCount){
            roles.add(PlayerRole.CITIZEN);
        }

        Collections.shuffle(roles);

        for (int i = 0; i < playerCount; i++){
            players.get(i).setRole(roles.get(i));
            players.get(i).setAlive(true);
        }
    }

    /**
     * 모든 플레이어의 투표 수를 0으로 초기화 합니다.
     */
    public void resetVoteCounts(){
        for(GamePlayer player : players){
            player.setVoteCount(0);
        }
    }

    /**
     * 플레이어 목록의 크기를 반환합니다.
     */
    public int size(){
        return players.size();
    }
}