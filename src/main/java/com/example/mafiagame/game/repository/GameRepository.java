package com.example.mafiagame.game.repository;

import com.example.mafiagame.game.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, String> {
    // 가장 최근 게임 반환 (중복 방지)
    Optional<Game> findFirstByRoomIdAndStatusOrderByStartTimeDesc(String roomId,
            com.example.mafiagame.game.domain.GameStatus status);
}
