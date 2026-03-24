package com.example.mafiagame.game.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.timer.GameTimerMeta;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;

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

    // Lua 스크립트: 메타 데이터를 원자적으로 저장
    private static final DefaultRedisScript<Long> SAVE_META_SCRIPT = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], 'phase', ARGV[1], 'currentPhase', ARGV[2], 'status', ARGV[3]); " +
                    "if ARGV[4] ~= '' then " +
                    "  redis.call('HSET', KEYS[1], 'phaseEndTime', ARGV[4]); " +
                    "else " +
                    "  redis.call('HDEL', KEYS[1], 'phaseEndTime'); " +
                    "end; " +
                    "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5])); " +
                    "return 1",
            Long.class);

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

    public void saveTimerToken(String gameId, String timerToken) {
        String metaKey = META_KEY_PREFIX + gameId;
        stringRedisTemplate.opsForHash().put(metaKey, TIMER_TOKEN_FIELD, timerToken);
        stringRedisTemplate.expire(metaKey, TTL);
    }

    public void clearTimerToken(String gameId) {
        String metaKey = META_KEY_PREFIX + gameId;
        stringRedisTemplate.opsForHash().delete(metaKey, TIMER_TOKEN_FIELD);
        stringRedisTemplate.expire(metaKey, TTL);
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
     * 메타 데이터를 Lua 스크립트로 원자적으로 저장
     */
    private void saveMeta(GameState gameState) {
        String metaKey = META_KEY_PREFIX + gameState.getGameId();
        String phaseEndTime = gameState.getPhaseEndTime() != null
                ? String.valueOf(gameState.getPhaseEndTime())
                : "";
        stringRedisTemplate.execute(
                SAVE_META_SCRIPT,
                List.of(metaKey),
                gameState.getGamePhase().name(),
                String.valueOf(gameState.getCurrentPhase()),
                gameState.getStatus().name(),
                phaseEndTime,
                String.valueOf(TTL.getSeconds()));
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
