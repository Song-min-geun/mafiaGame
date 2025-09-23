package com.example.mafiagame.game.domain;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.*;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonManagedReference;

@Entity
@Table(name = "games")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Game {
    @Id
    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private GameStatus status;

    @Column(name = "current_phase", nullable = false)
    @Builder.Default
    private int currentPhase = 0;

    @Column(name = "is_day", nullable = false)
    @Builder.Default
    private boolean isDay = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_phase", nullable = false)
    @Builder.Default
    private GamePhase gamePhase = GamePhase.DAY_DISCUSSION;

    @Column(name = "winner")
    private String winner;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Column(name = "has_doctor", nullable = false)
    @Builder.Default
    private boolean hasDoctor = false;

    @Column(name = "has_police", nullable = false)
    @Builder.Default
    private boolean hasPolice = false;

    @Builder.Default
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<GamePlayer> players = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<Vote> votes = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<NightAction> nightActions = new ArrayList<>();

    @Builder.Default
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonManagedReference
    private List<FinalVote> finalVotes = new ArrayList<>();

    // 타이머 관련 필드들
    @Transient
    @Builder.Default
    private int remainingTime = 60;

    @Transient
    private LocalDateTime phaseStartTime;

    @Transient
    private String votedPlayerId;

    // 시간 연장 관련 필드들
    @Transient
    @Builder.Default
    private Map<String, Boolean> timeExtensionsUsed = new HashMap<>();

    @Transient
    @Builder.Default
    private Map<String, Boolean> votingTimeExtensionsUsed = new HashMap<>();

    public void setIsDay(boolean isDay) {
        this.isDay = isDay;
    }

    public int getDayTimeLimit() {
        return 60; // 기본값 60초
    }

    public int getNightTimeLimit() {
        return 30; // 기본값 30초
    }
}
