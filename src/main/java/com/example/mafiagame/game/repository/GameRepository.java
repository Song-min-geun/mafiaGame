package com.example.mafiagame.game.repository;

import com.example.mafiagame.game.domain.Game;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GameRepository extends JpaRepository<Game, String> {
    Optional<Game> findByRoomIdAndStatus(String roomId, com.example.mafiagame.game.domain.GameStatus status);
}
