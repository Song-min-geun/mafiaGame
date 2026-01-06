package com.example.mafiagame.game.repository;

import com.example.mafiagame.game.domain.GameState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Slf4j
public class GameStateRepository {

    private final RedisTemplate<String, Object> redisTemplate;

    // Redis Key Prefix
    private static final String KEY_PREFIX = "game:state:";
    // TTL (게임 종료 후 자동 삭제 시간, 넉넉하게 1시간)
    private static final Duration TTL = Duration.ofHours(1);

    public void save(GameState gameState) {
        String key = KEY_PREFIX + gameState.getGameId();
        redisTemplate.opsForValue().set(key, gameState, TTL);
        // log.debug("Saved GameState to Redis: {}", key);
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
}
