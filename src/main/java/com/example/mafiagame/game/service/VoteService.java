package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.FinalVote;
import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.Vote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 투표 서비스
 * - 낮 투표, 최종 투표 처리
 * - Lua Script 기반 원자적 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VoteService {

    private final StringRedisTemplate stringRedisTemplate;
    private final GameQueryService gameQueryService;

    private static final String GAME_STATE_KEY_PREFIX = "game:state:";
    private static final String VOTE_KEY_PREFIX = "game:votes:";
    private static final String FINAL_VOTE_KEY_PREFIX = "game:finalvotes:";

    private static final String VOTE_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local votesKey = KEYS[2]
            local voterId = ARGV[1]
            local targetId = ARGV[2]

            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            redis.call('HSET', votesKey, voterId, targetId)
            redis.call('EXPIRE', votesKey, 1800)

            return 'OK'
            """;

    private static final String FINAL_VOTE_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local finalVotesKey = KEYS[2]
            local voterId = ARGV[1]
            local voteChoice = ARGV[2]

            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            redis.call('HSET', finalVotesKey, voterId, voteChoice)
            redis.call('EXPIRE', finalVotesKey, 1800)

            return 'OK'
            """;

    // ================= 투표 처리 =================

    /**
     * 낮 투표 (Lua Script 기반 원자적 처리)
     */
    public void vote(String gameId, String voterId, String targetId) {
        GameState gameState = gameQueryService.getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_VOTING) {
            return;
        }

        if (gameQueryService.findActivePlayerById(gameState, voterId) == null) {
            return;
        }

        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String votesKey = VOTE_KEY_PREFIX + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(VOTE_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            String result = stringRedisTemplate.execute(script, List.of(gameStateKey, votesKey), voterId, targetId);
            if ("OK".equals(result)) {
                log.debug("[투표] 성공: gameId={}, voterId={}, targetId={}", gameId, voterId, targetId);
            } else {
                log.warn("[투표] 실패: gameId={}, result={}", gameId, result);
            }
        } catch (Exception e) {
            log.error("[투표] Lua Script 오류: gameId={}", gameId, e);
        }
    }

    /**
     * 최종 투표 (Lua Script 기반 원자적 처리)
     */
    public void finalVote(String gameId, String voterId, String voteChoice) {
        GameState gameState = gameQueryService.getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.DAY_FINAL_VOTING) {
            return;
        }

        // 변론자 본인은 투표 불가
        if (gameQueryService.findActivePlayerById(gameState, voterId) == null
                || voterId.equals(gameState.getVotedPlayerId())) {
            return;
        }

        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String finalVotesKey = FINAL_VOTE_KEY_PREFIX + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(FINAL_VOTE_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            String result = stringRedisTemplate.execute(script, List.of(gameStateKey, finalVotesKey), voterId,
                    voteChoice);
            if ("OK".equals(result)) {
                log.debug("[최종투표] 성공: gameId={}, voterId={}, choice={}", gameId, voterId, voteChoice);
            } else {
                log.warn("[최종투표] 실패: gameId={}, result={}", gameId, result);
            }
        } catch (Exception e) {
            log.error("[최종투표] Lua Script 오류: gameId={}", gameId, e);
        }
    }

    // ================= Redis 동기화 =================

    /**
     * Redis Hash에서 투표 동기화
     */
    public void syncVotesFromRedis(GameState gameState) {
        String votesKey = VOTE_KEY_PREFIX + gameState.getGameId();
        try {
            var votes = stringRedisTemplate.opsForHash().entries(votesKey);
            gameState.getVotes().clear();
            votes.forEach((voterId, targetId) -> gameState.getVotes().add(Vote.builder()
                    .voterId((String) voterId)
                    .targetId((String) targetId)
                    .build()));
            log.debug("[동기화] 투표 {}건 로드: gameId={}", votes.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 투표 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    /**
     * Redis Hash에서 최종 투표 동기화
     */
    public void syncFinalVotesFromRedis(GameState gameState) {
        String finalVotesKey = FINAL_VOTE_KEY_PREFIX + gameState.getGameId();
        try {
            var votes = stringRedisTemplate.opsForHash().entries(finalVotesKey);
            gameState.getFinalVotes().clear();
            votes.forEach((voterId, voteChoice) -> gameState.getFinalVotes().add(FinalVote.builder()
                    .voterId((String) voterId)
                    .vote((String) voteChoice)
                    .build()));
            log.debug("[동기화] 최종투표 {}건 로드: gameId={}", votes.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 최종투표 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    /**
     * 투표 데이터 삭제
     */
    public void clearVotesFromRedis(String gameId) {
        stringRedisTemplate.delete(VOTE_KEY_PREFIX + gameId);
    }

    /**
     * 최종 투표 데이터 삭제
     */
    public void clearFinalVotesFromRedis(String gameId) {
        stringRedisTemplate.delete(FINAL_VOTE_KEY_PREFIX + gameId);
    }

    // ================= 투표 결과 계산 =================

    /**
     * 최다 득표자 계산 (List<Vote> 입력)
     */
    public List<String> getTopVotedPlayers(List<Vote> votes) {
        if (votes == null || votes.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Long> counts = votes.stream()
                .collect(Collectors.groupingBy(Vote::getTargetId, Collectors.counting()));
        if (counts.isEmpty()) {
            return new ArrayList<>();
        }
        long max = Collections.max(counts.values());
        return counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 최다 득표자 계산 (Map 입력)
     */
    public List<String> getTopVotedPlayers(Map<String, Long> counts) {
        if (counts == null || counts.isEmpty()) {
            return new ArrayList<>();
        }
        long max = Collections.max(counts.values());
        return counts.entrySet().stream()
                .filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey)
                .toList();
    }
}
