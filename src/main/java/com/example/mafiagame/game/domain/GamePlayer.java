package com.example.mafiagame.game.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

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
public class GamePlayer {
    private String playerId;        // 플레이어 ID (userLoginId)
    private String playerName;      // 플레이어 닉네임
    private PlayerRole role;        // 플레이어 역할
    @Builder.Default
    @JsonProperty("isAlive")
    private boolean isAlive = true;        // 생존 여부
    @Builder.Default
    @JsonProperty("isHost")
    private boolean isHost = false;         // 방장 여부
    @Builder.Default
    @JsonProperty("isReady")
    private boolean isReady = false;        // 준비 상태
    @Builder.Default
    private int voteCount = 0;          // 받은 투표 수
    private String targetPlayerId;  // 밤에 선택한 타겟 (마피아/의사/경찰용)
    

    public void setIsAlive(boolean isAlive) {
        this.isAlive = isAlive;
    }
    
    public void setIsReady(boolean isReady) {
        this.isReady = isReady;
    }
    
    public void setRole(PlayerRole role) {
        this.role = role;
    }
    
    public void setVoteCount(int voteCount) {
        this.voteCount = voteCount;
    }
}
