package com.example.mafiagame.game.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.timer.GameTimerMeta;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
@Slf4j
public class GameStateRepository {

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;

    // Redis Key Prefix
    private static final String KEY_PREFIX = "game:state:";
    private static final String META_KEY_PREFIX = "game:meta:";
    private static final String TIMER_TOKEN_FIELD = "timerToken";
    // TTL
    private static final Duration TTL = Duration.ofMinutes(30);

    public void save(GameState gameState) {
        String key = KEY_PREFIX + gameState.getGameId();
        redisTemplate.opsForValue().set(key, gameState, TTL);
        saveMeta(gameState);
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
        stringRedisTemplate.delete(META_KEY_PREFIX + gameId);
    }

    public List<GameState> findInProgressGames() {
        return scanKeys(KEY_PREFIX + "*").stream()
                .map(redisTemplate.opsForValue()::get)
                .filter(GameState.class::isInstance)
                .map(GameState.class::cast)
                .filter(gameState -> gameState.getStatus() == GameStatus.IN_PROGRESS)
                .filter(gameState -> gameState.getPhaseEndTime() != null)
                .collect(Collectors.toList());
    }

    public Optional<GameTimerMeta> findMeta(String gameId) {
        String metaKey = META_KEY_PREFIX + gameId;
        Map<Object, Object> entries = stringRedisTemplate.opsForHash().entries(metaKey);
        if (entries.isEmpty()) {
            return Optional.empty();
        }

        Object phase = entries.get("phase");
        Object currentPhase = entries.get("currentPhase");
        Object status = entries.get("status");
        if (phase == null || currentPhase == null || status == null) {
            return Optional.empty();
        }

        Object phaseEndTime = entries.get("phaseEndTime");
        Object timerToken = entries.get(TIMER_TOKEN_FIELD);

        return Optional.of(new GameTimerMeta(
                GamePhase.valueOf(phase.toString()),
                Integer.parseInt(currentPhase.toString()),
                GameStatus.valueOf(status.toString()),
                phaseEndTime != null ? Long.parseLong(phaseEndTime.toString()) : null,
                timerToken != null ? timerToken.toString() : null));
    }

    /**
     * 특정 플레이어가 참여 중인 게임 상태 조회
     * SCAN 기반으로 Redis 블로킹 방지
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
     * SCAN 기반으로 Redis 블로킹 방지
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
     * 메타 데이터를 순차 Redis 명령으로 저장 (호출자가 Lock 보유)
     */
    private void saveMeta(GameState gameState) {
        String metaKey = META_KEY_PREFIX + gameState.getGameId();
        Map<String, Object> fields = new HashMap<>();
        fields.put("phase", gameState.getGamePhase().name());
        fields.put("currentPhase", String.valueOf(gameState.getCurrentPhase()));
        fields.put("status", gameState.getStatus().name());
        stringRedisTemplate.opsForHash().putAll(metaKey, fields);

        if (gameState.getPhaseEndTime() != null) {
            stringRedisTemplate.opsForHash().put(metaKey, "phaseEndTime",
                    String.valueOf(gameState.getPhaseEndTime()));
        } else {
            stringRedisTemplate.opsForHash().delete(metaKey, "phaseEndTime");
        }
        stringRedisTemplate.expire(metaKey, TTL);
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
