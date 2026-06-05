package com.example.mafiagame.kafka.event;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 게임 종료 시 발행되는 Kafka 이벤트.
 *
 * <p>부스트 아이템 소진, 통계 집계 등 후속 처리에 활용된다.</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameEndedEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String gameId;
    private String roomId;
    private String winnerTeam;
    private List<String> playerIds;

    @Builder.Default
    private LocalDateTime occurredAt = LocalDateTime.now();
}
