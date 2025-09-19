package com.example.mafiagame.game.domain;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "games")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Game {
    @Id
    @Column(name = "game_id", nullable = false)
    private String gameId;                    // 게임 ID
    
    @Column(name = "room_id", nullable = false)
    private String roomId;                    // 방 ID (외래키)
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GameStatus status;                // 게임 상태
    
    @Column(name = "current_phase", nullable = false)
    @Builder.Default
    private int currentPhase = 0;             // 현재 페이즈 (1, 2, 3...)
    
    @Column(name = "is_day", nullable = false)
    @Builder.Default
    private boolean isDay = true;             // 낮/밤 구분 (낮이 기본값)
    
    @Enumerated(EnumType.STRING)
    @Column(name = "game_phase", nullable = false)
    @Builder.Default
    private GamePhase gamePhase = GamePhase.DAY_DISCUSSION; // 게임 페이즈 (대화/투표/반론/찬반/밤)
    
    @Column(name = "winner")
    private String winner;                    // 승리 팀 ("CITIZEN" or "MAFIA")
    
    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;          // 게임 시작 시간
    
    @Column(name = "end_time")
    private LocalDateTime endTime;            // 게임 종료 시간
    
    @Column(name = "max_players", nullable = false)
    private int maxPlayers;                   // 최대 플레이어 수
    
    @Column(name = "has_doctor", nullable = false)
    @Builder.Default
    private boolean hasDoctor = false;        // 의사 포함 여부
    
    @Column(name = "has_police", nullable = false)
    @Builder.Default
    private boolean hasPolice = false;        // 경찰 포함 여부

    @Transient
    private Players players;         // 플레이어 목록
    
    @Transient
    @Builder.Default
    private Map<String, String> votes = new HashMap<>();        // 투표 결과 (투표자 -> 대상)
    
    @Transient
    @Builder.Default
    private Map<String, String> nightActions = new HashMap<>(); // 밤 액션 결과 (액션자 -> 대상)
    
    @Transient
    @Builder.Default
    private Map<String, String> finalVotes = new HashMap<>();   // 최종 투표 결과 (투표자 -> 찬성/반대)
    
    // ❗ 추가: 게임 시간 관련 필드들
    @Transient
    @Builder.Default
    private int dayTimeLimit = 60;              // 낮 시간 제한 (초)
    
    @Transient
    @Builder.Default
    private int nightTimeLimit = 30;            // 밤 시간 제한 (초)
    
    @Transient
    @Builder.Default
    private int remainingTime = 60;             // 남은 시간 (초)
    
    @Transient
    private LocalDateTime phaseStartTime;       // 현재 페이즈 시작 시간
    
    @Transient
    @Builder.Default
    private Map<String, Boolean> timeExtensionsUsed = new HashMap<>(); // 플레이어별 시간 연장 사용 여부
    
    @Transient
    @Builder.Default
    private Map<String, Boolean> votingTimeExtensionsUsed = new HashMap<>(); // 투표 페이즈별 시간 연장 사용 여부
    
    @Transient
    private String votedPlayerId;                // 최다 득표자 ID (최후 변론용)
    
    // 수동 setter 메서드들
    public void setIsDay(boolean isDay) {
        this.isDay = isDay;
    }
    
    public void setGamePhase(GamePhase gamePhase) {
        this.gamePhase = gamePhase;
    }
    
    public void setHasDoctor(boolean hasDoctor) {
        this.hasDoctor = hasDoctor;
    }
    
    public void setHasPolice(boolean hasPolice) {
        this.hasPolice = hasPolice;
    }
    

}
