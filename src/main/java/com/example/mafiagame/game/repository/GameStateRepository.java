package com.example.mafiagame.game.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.game.domain.state.GameState;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class GameStateRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis Key Prefix
    private static final String KEY_PREFIX = "game:state:";
    // TTL
    private static final Duration TTL = Duration.ofMinutes(30);

    public void save(GameState gameState) {
        String key = KEY_PREFIX + gameState.getGameId();
        redisTemplate.opsForValue().set(key, gameState, TTL);
    }

    public Optional<GameState> findById(String gameId) {
        String key = KEY_PREFIX + gameId;
        Object result = redisTemplate.opsForValue().get(key);

        if (result instanceof GameState) {
            return Optional.of((GameState) result);
        }
        return Optional.empty();
    }

    public void delete(String gameId) {
        String key = KEY_PREFIX + gameId;
        redisTemplate.delete(key);
    }

    /**
     * 특정 플레이어가 참여 중인 게임 상태 조회
     */
    public Optional<GameState> findByPlayerId(String playerId) {
        // Redis에서 모든 게임 키 조회
        var keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }

        for (String key : keys) {
            Object result = redisTemplate.opsForValue().get(key);
            if (result instanceof GameState gameState) {
                // 해당 게임에 플레이어가 있는지 확인
                boolean isPlayer = gameState.getPlayers().stream()
                        .anyMatch(p -> p.getPlayerId().equals(playerId));
                if (isPlayer) {
                    return Optional.of(gameState);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 특정 방의 게임 상태 조회
     */
    public Optional<GameState> findByRoomId(String roomId) {
        var keys = redisTemplate.keys(KEY_PREFIX + "*");
        if (keys == null || keys.isEmpty()) {
            return Optional.empty();
        }

        for (String key : keys) {
            Object result = redisTemplate.opsForValue().get(key);
            if (result instanceof GameState gameState) {
                if (roomId.equals(gameState.getRoomId())) {
                    return Optional.of(gameState);
                }
            }
        }
        return Optional.empty();
    }
}
