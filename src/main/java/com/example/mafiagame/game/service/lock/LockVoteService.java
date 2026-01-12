package com.example.mafiagame.game.service.lock;

import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.Vote;
import com.example.mafiagame.game.repository.GameStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 동시성 제어 방식 비교를 위한 투표 서비스
 * 
 * 3가지 방식으로 동일한 투표 로직을 구현하여 비교:
 * 1. 낙관적 락 (Redis WATCH/MULTI)
 * 2. 분산 락 (Redisson RLock)
 * 3. Redis Lua Script (원자적 연산)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LockVoteService {

    private final GameStateRepository gameStateRepository;
    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate; // Lua Script용 (String 직렬화)
    private final RedissonClient redissonClient;

    private static final String GAME_STATE_KEY_PREFIX = "game:state:";
    private static final String LOCK_KEY_PREFIX = "lock:game:";

    // ==================== 1. 낙관적 락 (Optimistic Lock) ====================
    /**
     * Redis WATCH/MULTI를 사용한 낙관적 락
     * 
     * 동작 방식:
     * 1. WATCH로 키 감시 시작
     * 2. 데이터 읽기 및 수정
     * 3. MULTI/EXEC으로 트랜잭션 실행
     * 4. 다른 클라이언트가 WATCH 이후 키를 수정했으면 EXEC 실패 → 재시도
     * 
     * 장점: 경쟁이 적을 때 락 오버헤드 없음
     * 단점: 경쟁이 많으면 재시도가 많아짐
     */
    public boolean voteWithOptimisticLock(String gameId, String voterId, String targetId) {
        String key = GAME_STATE_KEY_PREFIX + gameId;
        int maxRetries = 3;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            try {
                // SessionCallback을 사용한 WATCH/MULTI/EXEC
                Boolean success = redisTemplate
                        .execute(new org.springframework.data.redis.core.SessionCallback<Boolean>() {
                            @Override
                            @SuppressWarnings("unchecked")
                            public Boolean execute(org.springframework.data.redis.core.RedisOperations operations) {
                                operations.watch(key);

                                GameState gameState = (GameState) operations.opsForValue().get(key);
                                if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_VOTING) {
                                    operations.unwatch();
                                    return false;
                                }

                                // 투표 수정
                                gameState.getVotes().removeIf(v -> v.getVoterId().equals(voterId));
                                gameState.getVotes().add(Vote.builder().voterId(voterId).targetId(targetId).build());

                                // 트랜잭션 시작
                                operations.multi();
                                operations.opsForValue().set(key, gameState);

                                // 트랜잭션 실행 (WATCH 이후 키가 변경되었으면 null 반환)
                                List<Object> results = operations.exec();
                                return results != null && !results.isEmpty();
                            }
                        });

                if (Boolean.TRUE.equals(success)) {
                    log.info("[낙관적 락] 투표 성공: gameId={}, voterId={}, attempt={}", gameId, voterId, attempt + 1);
                    return true;
                }

                log.warn("[낙관적 락] 충돌 발생, 재시도: gameId={}, attempt={}", gameId, attempt + 1);

            } catch (Exception e) {
                log.error("[낙관적 락] 오류 발생: gameId={}, error={}", gameId, e.getMessage());
            }
        }

        log.error("[낙관적 락] 최대 재시도 초과: gameId={}, voterId={}", gameId, voterId);
        return false;
    }

    // ==================== 2. 분산 락 (Distributed Lock - Redisson)
    // ====================
    /**
     * Redisson RLock을 사용한 분산 락
     * 
     * 동작 방식:
     * 1. 락 획득 시도 (타임아웃 설정)
     * 2. 락 획득 성공 시 로직 실행
     * 3. finally에서 락 해제
     * 
     * 장점: 다중 서버 환경에서 안전, Watch Dog로 자동 연장
     * 단점: 락 획득/해제 오버헤드, 데드락 가능성 (타임아웃으로 방지)
     */
    public boolean voteWithDistributedLock(String gameId, String voterId, String targetId) {
        String lockKey = LOCK_KEY_PREFIX + gameId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 락 획득 시도: 최대 5초 대기, 획득 후 10초 후 자동 해제
            boolean acquired = lock.tryLock(5, 10, TimeUnit.SECONDS);

            if (!acquired) {
                log.warn("[분산 락] 락 획득 실패: gameId={}, voterId={}", gameId, voterId);
                return false;
            }

            try {
                // 락 획득 성공 - 안전하게 데이터 수정
                GameState gameState = gameStateRepository.findById(gameId).orElse(null);
                if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_VOTING) {
                    return false;
                }

                gameState.getVotes().removeIf(v -> v.getVoterId().equals(voterId));
                gameState.getVotes().add(Vote.builder().voterId(voterId).targetId(targetId).build());

                gameStateRepository.save(gameState);
                log.info("[분산 락] 투표 성공: gameId={}, voterId={}", gameId, voterId);
                return true;

            } finally {
                // 락 해제 - 반드시 finally에서 실행
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[분산 락] 인터럽트 발생: gameId={}", gameId);
            return false;
        }
    }

    // ==================== 3. Redis Lua Script (원자적 연산) ====================
    /**
     * Lua Script를 사용한 원자적 투표 처리
     * 
     * 동작 방식:
     * 1. 스크립트 전체가 Redis 내부에서 원자적으로 실행
     * 2. 읽기 → 수정 → 쓰기가 단일 명령처럼 처리됨
     * 
     * 장점: 가장 빠름, 락 불필요, 네트워크 왕복 1회
     * 단점: Lua 스크립트 작성 필요, 복잡한 로직 디버깅 어려움
     * 
     * 주의: Jackson 타입 정보가 포함된 JSON은 Lua cjson으로 파싱 불가하므로,
     * 투표는 별도 Redis Hash에 저장하는 방식 사용
     */
    private static final String VOTE_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local votesKey = KEYS[2]
            local voterId = ARGV[1]
            local targetId = ARGV[2]

            -- 게임 상태 존재 확인
            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            -- 투표 저장 (Hash: voterId -> targetId)
            -- HSET은 원자적으로 실행됨
            redis.call('HSET', votesKey, voterId, targetId)
            redis.call('EXPIRE', votesKey, 1800)

            return 'OK'
            """;

    /**
     * 투표 결과를 GameState와 동기화하는 스크립트
     */
    private static final String SYNC_VOTES_SCRIPT = """
            local votesKey = KEYS[1]
            local votes = redis.call('HGETALL', votesKey)
            return votes
            """;

    public boolean voteWithLuaScript(String gameId, String voterId, String targetId) {
        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String votesKey = "game:votes:" + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(VOTE_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            // StringRedisTemplate 사용 - Jackson 역직렬화 없이 순수 String으로 처리
            String result = stringRedisTemplate.execute(
                    script,
                    List.of(gameStateKey, votesKey),
                    voterId, targetId);

            if ("OK".equals(result)) {
                log.info("[Lua Script] 투표 성공: gameId={}, voterId={}", gameId, voterId);

                // GameState에도 투표 반영 (비동기로 처리 가능)
                syncVotesToGameState(gameId);
                return true;
            } else {
                log.warn("[Lua Script] 투표 실패: gameId={}, result={}", gameId, result);
                return false;
            }

        } catch (Exception e) {
            log.error("[Lua Script] 오류 발생: gameId={}, error={}", gameId, e.getMessage());
            return false;
        }
    }

    /**
     * Redis Hash에 저장된 투표를 GameState에 동기화
     * 실제 서비스에서는 페이즈 전환 시점에만 호출하면 됨
     */
    private void syncVotesToGameState(String gameId) {
        String votesKey = "game:votes:" + gameId;

        try {
            // StringRedisTemplate으로 Hash에서 모든 투표 가져오기
            var votes = stringRedisTemplate.opsForHash().entries(votesKey);

            // GameState 업데이트
            gameStateRepository.findById(gameId).ifPresent(gameState -> {
                gameState.getVotes().clear();
                votes.forEach((voterId, targetId) -> gameState.getVotes().add(Vote.builder()
                        .voterId((String) voterId)
                        .targetId((String) targetId)
                        .build()));
                gameStateRepository.save(gameState);
            });
        } catch (Exception e) {
            log.error("[Lua Script] 투표 동기화 실패: gameId={}", gameId, e);
        }
    }

    // ==================== 비교용 헬퍼 메서드 ====================

    /**
     * 동시성 테스트를 위한 메서드
     * 지정된 방식으로 투표 실행
     */
    public boolean vote(String gameId, String voterId, String targetId, LockType lockType) {
        long start = System.nanoTime();
        boolean result;

        result = switch (lockType) {
            case OPTIMISTIC -> voteWithOptimisticLock(gameId, voterId, targetId);
            case DISTRIBUTED -> voteWithDistributedLock(gameId, voterId, targetId);
            case LUA_SCRIPT -> voteWithLuaScript(gameId, voterId, targetId);
        };

        long elapsed = (System.nanoTime() - start) / 1_000_000; // ms
        log.info("[{}] 실행 시간: {}ms, 결과: {}", lockType, elapsed, result);

        return result;
    }

    public enum LockType {
        OPTIMISTIC, // 낙관적 락
        DISTRIBUTED, // 분산 락 (Redisson)
        LUA_SCRIPT // Lua Script
    }
}
