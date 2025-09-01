package com.example.mafiagame.game.domain;

import java.time.LocalDateTime;
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
public class Game {
    private String gameId;                    // 게임 ID
    private String roomId;                    // 방 ID
    private GameStatus status;                // 게임 상태
    private List<GamePlayer> players;         // 플레이어 목록
    @Builder.Default
    private int currentPhase = 0;             // 현재 페이즈 (1, 2, 3...)
    @Builder.Default
    private boolean isNight = false;          // 밤/낮 구분
    @Builder.Default
    private int nightCount = 0;               // 밤 카운트
    @Builder.Default
    private int dayCount = 0;                 // 낮 카운트
    @Builder.Default
    private Map<String, String> votes = new HashMap<>();        // 투표 결과 (투표자 -> 대상)
    @Builder.Default
    private Map<String, String> nightActions = new HashMap<>(); // 밤 액션 결과 (액션자 -> 대상)
    private String winner;                    // 승리 팀 ("CITIZEN" or "MAFIA")
    private LocalDateTime startTime;          // 게임 시작 시간
    private LocalDateTime endTime;            // 게임 종료 시간
    private int maxPlayers;                   // 최대 플레이어 수
    @Builder.Default
    private boolean hasDoctor = false;        // 의사 포함 여부
    @Builder.Default
    private boolean hasPolice = false;        // 경찰 포함 여부
    
    // 수동 setter 메서드들
    public void setIsNight(boolean isNight) {
        this.isNight = isNight;
    }
    
    public void setHasDoctor(boolean hasDoctor) {
        this.hasDoctor = hasDoctor;
    }
    
    public void setHasPolice(boolean hasPolice) {
        this.hasPolice = hasPolice;
    }
    

}
