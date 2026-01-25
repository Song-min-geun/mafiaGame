package com.example.mafiagame.game.domain.entity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.domain.state.Team;
import com.fasterxml.jackson.annotation.JsonIgnore;

@Entity
@Table(name = "games")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Game {
    @Id
    @Column(name = "game_id", nullable = false)
    private String gameId;

    @Column(name = "room_id", nullable = false)
    private String roomId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "winner")
    private Team winnerTeam;

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Builder.Default
    @OneToMany(mappedBy = "game", cascade = CascadeType.ALL, orphanRemoval = true)
    @JsonIgnore
    private List<GamePlayer> players = new ArrayList<>();

    /**
     * 새 게임 생성 - gameId, status, startTime 자동 설정
     */
    public static Game createNew(String roomId) {
        return Game.builder()
                .gameId("game_" + System.currentTimeMillis() + "_" + new java.util.Random().nextInt(1000))
                .roomId(roomId)
                .status(GameStatus.IN_PROGRESS)
                .startTime(LocalDateTime.now())
                .build();
    }
}