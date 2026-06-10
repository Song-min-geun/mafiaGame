package com.example.mafiagame.game.repository;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SCAN 기반의 복잡한 게임 상태 조회 전용 레포지토리
 */
@Repository
@Slf4j
public class GameQueryRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    public GameQueryRepository(
            @Qualifier("coreRedisTemplate") RedisTemplate<String, Object> redisTemplate,
            @Qualifier("coreStringRedisTemplate") StringRedisTemplate stringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "game:state:";

    /**
     * 진행 중인 모든 게임 상태 조회
     */
    public List<GameState> findInProgressGames() {
        return scanKeys(KEY_PREFIX + "*").stream()
                .map(redisTemplate.opsForValue()::get)
                .filter(GameState.class::isInstance)
                .map(GameState.class::cast)
                .filter(gameState -> gameState.getStatus() == GameStatus.IN_PROGRESS)
                .filter(gameState -> gameState.getPhaseEndTime() != null)
                .collect(Collectors.toList());
    }

    /**
     * 특정 플레이어가 참여 중인 게임 상태 조회
     */
    public Optional<GameState> findByPlayerId(String playerId) {
        Set<String> keys = scanKeys(KEY_PREFIX + "*");
        if (keys.isEmpty()) {
            return Optional.empty();
        }

        for (String key : keys) {
            Object result = redisTemplate.opsForValue().get(key);
            if (result instanceof GameState gameState) {
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
        Set<String> keys = scanKeys(KEY_PREFIX + "*");
        if (keys.isEmpty()) {
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

    /**
     * keys() 대신 SCAN을 사용하여 Redis 블로킹 방지
     */
    private Set<String> scanKeys(String pattern) {
        Set<String> keys = new HashSet<>();
        try (var cursor = stringRedisTemplate.scan(
                org.springframework.data.redis.core.ScanOptions.scanOptions()
                        .match(pattern)
                        .count(100)
                        .build())) {
            cursor.forEachRemaining(keys::add);
        }
        return keys;
    }
}
