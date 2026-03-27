package com.example.mafiagame.game.repository;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import com.example.mafiagame.game.timer.GameTimerJob;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis ZSET 기반 게임 타이머 저장소.
 * 모든 원자적 연산은 Redisson Lock을 사용하여 동시성을 보장한다.
 */
@Repository
@RequiredArgsConstructor
@Slf4j
public class GameTimerRepository {

    private static final String WAITING_KEY = "game:timer:waiting";
    private static final String PROCESSING_KEY = "game:timer:processing";
    private static final String CURRENT_TIMER_KEY_PREFIX = "game:timer:current:";
    private static final String META_KEY_PREFIX = "game:meta:";
    private static final String TIMER_TOKEN_FIELD = "timerToken";
    private static final Duration CURRENT_TIMER_TTL = Duration.ofMinutes(30);

    private static final String TIMER_LOCK_KEY = "lock:timer:global";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;

    /**
     * 타이머를 스케줄링한다. 기존 타이머가 있으면 제거 후 새로 등록.
     * 호출자(advancePhase, startGame 등)가 이미 게임별 Redisson Lock을 보유하므로
     * 별도 Lock 없이 순차 Redis 명령으로 처리한다.
     */
    public void schedule(GameTimerJob timerJob, long executeAtMillis) {
        String currentTimerKey = currentTimerKey(timerJob.gameId());
        String metaKey = metaKey(timerJob.gameId());

        // 기존 타이머 제거
        String current = stringRedisTemplate.opsForValue().get(currentTimerKey);
        if (current != null) {
            stringRedisTemplate.opsForZSet().remove(WAITING_KEY, current);
            stringRedisTemplate.opsForZSet().remove(PROCESSING_KEY, current);
        }

        // 메타 데이터에 timerToken 저장
        stringRedisTemplate.opsForHash().put(metaKey, TIMER_TOKEN_FIELD, timerJob.timerToken());
        stringRedisTemplate.expire(metaKey, CURRENT_TIMER_TTL);

        // current 키 설정 및 ZSET 등록
        stringRedisTemplate.opsForValue().set(currentTimerKey, timerJob.toMember(),
                CURRENT_TIMER_TTL);
        stringRedisTemplate.opsForZSet().add(WAITING_KEY, timerJob.toMember(), executeAtMillis);
    }

    /**
     * 타이머를 중지한다. 호출자가 이미 게임별 Lock을 보유.
     */
    public void stop(String gameId) {
        String currentTimerKey = currentTimerKey(gameId);
        String metaKey = metaKey(gameId);

        String current = stringRedisTemplate.opsForValue().get(currentTimerKey);
        if (current != null) {
            stringRedisTemplate.opsForZSet().remove(WAITING_KEY, current);
            stringRedisTemplate.opsForZSet().remove(PROCESSING_KEY, current);
        }

        stringRedisTemplate.opsForHash().delete(metaKey, TIMER_TOKEN_FIELD);
        stringRedisTemplate.expire(metaKey, CURRENT_TIMER_TTL);
        stringRedisTemplate.delete(currentTimerKey);
    }

    /**
     * 처리 완료된 타이머를 ACK 처리한다.
     * Worker에서 호출되므로 글로벌 타이머 Lock 사용.
     */
    public void ack(GameTimerJob timerJob) {
        RLock lock = redissonClient.getLock(TIMER_LOCK_KEY);

        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("[ack] 타이머 락 획득 실패: gameId={}", timerJob.gameId());
                return;
            }

            stringRedisTemplate.opsForZSet().remove(PROCESSING_KEY, timerJob.toMember());

            String current = stringRedisTemplate.opsForValue().get(currentTimerKey(timerJob.gameId()));
            if (current != null && current.equals(timerJob.toMember())) {
                stringRedisTemplate.delete(currentTimerKey(timerJob.gameId()));
                stringRedisTemplate.opsForHash().delete(metaKey(timerJob.gameId()), TIMER_TOKEN_FIELD);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[ack] 타이머 락 인터럽트: gameId={}", timerJob.gameId(), e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 현재 타이머인 경우에만 waiting 큐로 재등록한다.
     * Worker에서 호출되므로 글로벌 타이머 Lock 사용.
     */
    public boolean requeueIfCurrent(GameTimerJob timerJob, long executeAtMillis) {
        RLock lock = redissonClient.getLock(TIMER_LOCK_KEY);

        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("[requeue] 타이머 락 획득 실패: gameId={}", timerJob.gameId());
                return false;
            }

            String current = stringRedisTemplate.opsForValue().get(currentTimerKey(timerJob.gameId()));
            if (current == null || !current.equals(timerJob.toMember())) {
                return false;
            }

            stringRedisTemplate.opsForZSet().remove(PROCESSING_KEY, timerJob.toMember());
            stringRedisTemplate.opsForZSet().add(WAITING_KEY, timerJob.toMember(), executeAtMillis);
            return true;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[requeue] 타이머 락 인터럽트: gameId={}", timerJob.gameId(), e);
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 만료된 타이머를 waiting에서 processing으로 이동한다.
     * Worker에서 호출되므로 글로벌 타이머 Lock 사용.
     */
    public List<GameTimerJob> claimDueTimers(long nowMillis, int batchSize, long leaseMillis) {
        long leaseUntil = nowMillis + leaseMillis;
        RLock lock = redissonClient.getLock(TIMER_LOCK_KEY);

        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("[claimDueTimers] 타이머 락 획득 실패");
                return Collections.emptyList();
            }

            Set<String> due = stringRedisTemplate.opsForZSet()
                    .rangeByScore(WAITING_KEY, Double.NEGATIVE_INFINITY, nowMillis, 0, batchSize);
            if (due == null || due.isEmpty()) {
                return Collections.emptyList();
            }

            for (String member : due) {
                stringRedisTemplate.opsForZSet().remove(WAITING_KEY, member);
                stringRedisTemplate.opsForZSet().add(PROCESSING_KEY, member, leaseUntil);
            }

            return due.stream()
                    .map(GameTimerJob::fromMember)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[claimDueTimers] 타이머 락 인터럽트", e);
            return Collections.emptyList();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * processing에서 lease가 만료된 타이머를 재수거한다.
     * Worker에서 호출되므로 글로벌 타이머 Lock 사용.
     */
    public List<GameTimerJob> claimExpiredProcessing(long nowMillis, int batchSize) {
        RLock lock = redissonClient.getLock(TIMER_LOCK_KEY);

        try {
            if (!lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                log.warn("[claimExpiredProcessing] 타이머 락 획득 실패");
                return Collections.emptyList();
            }

            Set<String> expired = stringRedisTemplate.opsForZSet()
                    .rangeByScore(PROCESSING_KEY, Double.NEGATIVE_INFINITY, nowMillis, 0, batchSize);
            if (expired == null || expired.isEmpty()) {
                return Collections.emptyList();
            }

            for (String member : expired) {
                stringRedisTemplate.opsForZSet().remove(PROCESSING_KEY, member);
            }

            return expired.stream()
                    .map(GameTimerJob::fromMember)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[claimExpiredProcessing] 타이머 락 인터럽트", e);
            return Collections.emptyList();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 현재 타이머 여부를 확인한다 (읽기 전용, Lock 불필요).
     */
    public boolean isCurrentTimer(GameTimerJob timerJob) {
        String current = stringRedisTemplate.opsForValue().get(currentTimerKey(timerJob.gameId()));
        return timerJob.toMember().equals(current);
    }

    /**
     * 해당 게임에 스케줄된 타이머가 있는지 확인한다 (읽기 전용, Lock 불필요).
     */
    public boolean hasScheduledTimer(String gameId) {
        String current = stringRedisTemplate.opsForValue().get(currentTimerKey(gameId));
        if (current == null) {
            return false;
        }

        Double waitingScore = stringRedisTemplate.opsForZSet().score(WAITING_KEY, current);
        if (waitingScore != null) {
            return true;
        }

        Double processingScore = stringRedisTemplate.opsForZSet().score(PROCESSING_KEY, current);
        return processingScore != null;
    }

    private String currentTimerKey(String gameId) {
        return CURRENT_TIMER_KEY_PREFIX + gameId;
    }

    private String metaKey(String gameId) {
        return META_KEY_PREFIX + gameId;
    }
}
