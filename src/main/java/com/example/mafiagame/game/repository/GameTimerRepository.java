package com.example.mafiagame.game.repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.game.timer.GameTimerJob;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class GameTimerRepository {

    private static final String WAITING_KEY = "game:timer:waiting";
    private static final String PROCESSING_KEY = "game:timer:processing";
    private static final String CURRENT_TIMER_KEY_PREFIX = "game:timer:current:";
    private static final Duration CURRENT_TIMER_TTL = Duration.ofMinutes(30);

    private static final DefaultRedisScript<Long> SCHEDULE_TIMER_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[2]); " +
                    "if current then " +
                    "  redis.call('ZREM', KEYS[1], current); " +
                    "  redis.call('ZREM', KEYS[3], current); " +
                    "end; " +
                    "redis.call('SET', KEYS[2], ARGV[1], 'EX', tonumber(ARGV[3])); " +
                    "redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[1]); " +
                    "return 1;",
            Long.class);

    private static final DefaultRedisScript<Long> STOP_TIMER_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[2]); " +
                    "if current then " +
                    "  redis.call('ZREM', KEYS[1], current); " +
                    "  redis.call('ZREM', KEYS[3], current); " +
                    "end; " +
                    "redis.call('DEL', KEYS[2]); " +
                    "return 1;",
            Long.class);

    private static final DefaultRedisScript<Long> ACK_TIMER_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREM', KEYS[1], ARGV[1]); " +
                    "local current = redis.call('GET', KEYS[2]); " +
                    "if current and current == ARGV[1] then " +
                    "  redis.call('DEL', KEYS[2]); " +
                    "end; " +
                    "return 1;",
            Long.class);

    private static final DefaultRedisScript<Long> REQUEUE_TIMER_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[2]); " +
                    "if not current or current ~= ARGV[1] then " +
                    "  return 0; " +
                    "end; " +
                    "redis.call('ZADD', KEYS[1], tonumber(ARGV[2]), ARGV[1]); " +
                    "return 1;",
            Long.class);

    private static final DefaultRedisScript<List> CLAIM_DUE_TIMERS_SCRIPT = new DefaultRedisScript<>(
            "local due = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2])); " +
                    "for _, member in ipairs(due) do " +
                    "  redis.call('ZREM', KEYS[1], member); " +
                    "  redis.call('ZADD', KEYS[2], tonumber(ARGV[3]), member); " +
                    "end; " +
                    "return due;",
            List.class);

    private static final DefaultRedisScript<List> CLAIM_EXPIRED_PROCESSING_SCRIPT = new DefaultRedisScript<>(
            "local expired = redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, tonumber(ARGV[2])); " +
                    "for _, member in ipairs(expired) do " +
                    "  redis.call('ZREM', KEYS[1], member); " +
                    "end; " +
                    "return expired;",
            List.class);

    private final StringRedisTemplate stringRedisTemplate;

    public void schedule(GameTimerJob timerJob, long executeAtMillis) {
        stringRedisTemplate.execute(
                SCHEDULE_TIMER_SCRIPT,
                List.of(WAITING_KEY, currentTimerKey(timerJob.gameId()), PROCESSING_KEY),
                timerJob.toMember(),
                String.valueOf(executeAtMillis),
                String.valueOf(CURRENT_TIMER_TTL.getSeconds()));
    }

    public void stop(String gameId) {
        stringRedisTemplate.execute(
                STOP_TIMER_SCRIPT,
                List.of(WAITING_KEY, currentTimerKey(gameId), PROCESSING_KEY));
    }

    public void ack(GameTimerJob timerJob) {
        stringRedisTemplate.execute(
                ACK_TIMER_SCRIPT,
                List.of(PROCESSING_KEY, currentTimerKey(timerJob.gameId())),
                timerJob.toMember());
    }

    public boolean requeueIfCurrent(GameTimerJob timerJob, long executeAtMillis) {
        Long result = stringRedisTemplate.execute(
                REQUEUE_TIMER_SCRIPT,
                List.of(WAITING_KEY, currentTimerKey(timerJob.gameId())),
                timerJob.toMember(),
                String.valueOf(executeAtMillis));
        return result != null && result == 1L;
    }

    public List<GameTimerJob> claimDueTimers(long nowMillis, int batchSize, long leaseMillis) {
        long leaseUntil = nowMillis + leaseMillis;
        List<String> members = rawMembers(CLAIM_DUE_TIMERS_SCRIPT, nowMillis, batchSize, leaseUntil);
        return members.stream()
                .map(GameTimerJob::fromMember)
                .toList();
    }

    public List<GameTimerJob> claimExpiredProcessing(long nowMillis, int batchSize) {
        List<String> members = rawMembers(CLAIM_EXPIRED_PROCESSING_SCRIPT, nowMillis, batchSize);
        return members.stream()
                .map(GameTimerJob::fromMember)
                .toList();
    }

    public boolean isCurrentTimer(GameTimerJob timerJob) {
        String current = stringRedisTemplate.opsForValue().get(currentTimerKey(timerJob.gameId()));
        return timerJob.toMember().equals(current);
    }

    @SuppressWarnings("unchecked")
    private List<String> rawMembers(DefaultRedisScript<List> script, long nowMillis, int batchSize) {
        List<String> members = (List<String>) stringRedisTemplate.execute(
                script,
                List.of(PROCESSING_KEY),
                String.valueOf(nowMillis),
                String.valueOf(batchSize));
        return members != null ? members : Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    private List<String> rawMembers(DefaultRedisScript<List> script, long nowMillis, int batchSize, long leaseUntil) {
        List<String> members = (List<String>) stringRedisTemplate.execute(
                script,
                List.of(WAITING_KEY, PROCESSING_KEY),
                String.valueOf(nowMillis),
                String.valueOf(batchSize),
                String.valueOf(leaseUntil));
        return members != null ? members : Collections.emptyList();
    }

    private String currentTimerKey(String gameId) {
        return CURRENT_TIMER_KEY_PREFIX + gameId;
    }
}
