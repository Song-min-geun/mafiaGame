package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.GamePlayerState;
import com.example.mafiagame.game.domain.GameState;
import com.example.mafiagame.game.domain.NightAction;
import com.example.mafiagame.game.domain.PlayerRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 밤 행동 서비스
 * - 마피아/의사/경찰 밤 행동 처리
 * - Lua Script 기반 원자적 처리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NightActionService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SimpMessagingTemplate messagingTemplate;
    private final GameQueryService gameQueryService;
    private final VoteService voteService;

    private static final String GAME_STATE_KEY_PREFIX = "game:state:";
    private static final String NIGHT_ACTION_KEY_PREFIX = "game:nightactions:";

    private static final String NIGHT_ACTION_LUA_SCRIPT = """
            local gameStateKey = KEYS[1]
            local nightActionsKey = KEYS[2]
            local actorId = ARGV[1]
            local targetId = ARGV[2]

            local exists = redis.call('EXISTS', gameStateKey)
            if exists == 0 then
                return 'ERROR:GAME_NOT_FOUND'
            end

            redis.call('HSET', nightActionsKey, actorId, targetId)
            redis.call('EXPIRE', nightActionsKey, 1800)

            return 'OK'
            """;

    // ================= 밤 행동 처리 =================

    /**
     * 밤 행동 (Lua Script 기반 원자적 처리)
     */
    public void nightAction(String gameId, String actorId, String targetId) {
        GameState gameState = gameQueryService.getGameState(gameId);
        if (gameState == null || gameState.getGamePhase() != GamePhase.NIGHT_ACTION) {
            return;
        }

        GamePlayerState actor = gameQueryService.findActivePlayerById(gameState, actorId);
        if (actor == null || actor.getRole() == PlayerRole.CITIZEN) {
            return;
        }

        String gameStateKey = GAME_STATE_KEY_PREFIX + gameId;
        String nightActionsKey = NIGHT_ACTION_KEY_PREFIX + gameId;

        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(NIGHT_ACTION_LUA_SCRIPT);
        script.setResultType(String.class);

        try {
            String result = stringRedisTemplate.execute(script, List.of(gameStateKey, nightActionsKey), actorId,
                    targetId);
            if ("OK".equals(result)) {
                log.debug("[밤행동] 성공: gameId={}, actorId={}, targetId={}", gameId, actorId, targetId);

                // 경찰은 즉시 결과 확인
                if (actor.getRole() == PlayerRole.POLICE) {
                    GamePlayerState target = gameQueryService.findPlayerById(gameState, targetId);
                    if (target != null) {
                        sendPoliceInvestigationResult(actor, target);
                    }
                }
            } else {
                log.warn("[밤행동] 실패: gameId={}, result={}", gameId, result);
            }
        } catch (Exception e) {
            log.error("[밤행동] Lua Script 오류: gameId={}", gameId, e);
        }
    }

    // ================= Redis 동기화 =================

    /**
     * Redis Hash에서 밤 행동 동기화
     */
    public void syncNightActionsFromRedis(GameState gameState) {
        String nightActionsKey = NIGHT_ACTION_KEY_PREFIX + gameState.getGameId();
        try {
            var actions = stringRedisTemplate.opsForHash().entries(nightActionsKey);
            gameState.getNightActions().clear();
            actions.forEach((actorId, targetId) -> gameState.getNightActions().add(NightAction.builder()
                    .actorId((String) actorId)
                    .targetId((String) targetId)
                    .build()));
            log.debug("[동기화] 밤행동 {}건 로드: gameId={}", actions.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 밤행동 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    /**
     * 밤 행동 데이터 삭제
     */
    public void clearNightActionsFromRedis(String gameId) {
        stringRedisTemplate.delete(NIGHT_ACTION_KEY_PREFIX + gameId);
    }

    // ================= 밤 행동 결과 처리 =================

    /**
     * 밤 행동 결과 처리 (마피아 공격, 의사 치료)
     */
    public void processNightActions(GameState gameState) {
        // 마피아 투표 집계
        Map<String, Long> mafiaVotes = gameState.getNightActions().stream()
                .filter(action -> {
                    GamePlayerState p = gameQueryService.findPlayerById(gameState, action.getActorId());
                    return p != null && p.getRole() == PlayerRole.MAFIA;
                })
                .collect(Collectors.groupingBy(NightAction::getTargetId, Collectors.counting()));

        String mafiaTargetId = voteService.getTopVotedPlayers(mafiaVotes).stream().findFirst().orElse(null);

        // 의사 치료 대상
        String doctorTargetId = gameState.getNightActions().stream()
                .filter(action -> {
                    GamePlayerState p = gameQueryService.findPlayerById(gameState, action.getActorId());
                    return p != null && p.getRole() == PlayerRole.DOCTOR;
                })
                .map(NightAction::getTargetId)
                .findFirst()
                .orElse(null);

        // 마피아 공격 처리 (의사가 치료하지 않은 경우)
        if (mafiaTargetId != null && !mafiaTargetId.equals(doctorTargetId)) {
            GamePlayerState killed = gameQueryService.findPlayerById(gameState, mafiaTargetId);
            if (killed != null) {
                killed.setAlive(false);
                sendSystemMessage(gameState.getRoomId(), "지난 밤, " + killed.getPlayerName() + "님이 마피아의 공격으로 사망했습니다.");
            }
        } else {
            sendSystemMessage(gameState.getRoomId(), "지난 밤, 아무 일도 일어나지 않았습니다.");
        }
    }

    // ================= 메시지 전송 =================

    private void sendPoliceInvestigationResult(GamePlayerState police, GamePlayerState target) {
        sendPrivateMessage(police.getPlayerId(), Map.of(
                "type", "PRIVATE_MESSAGE",
                "messageType", "POLICE_INVESTIGATION",
                "content", String.format("경찰 조사 결과: %s님은 [ %s ] 입니다.",
                        target.getPlayerName(), target.getRole() == PlayerRole.MAFIA ? "마피아" : "시민")));
    }

    private void sendSystemMessage(String roomId, String content) {
        messagingTemplate.convertAndSend("/topic/room." + roomId, Map.of("type", "SYSTEM", "content", content));
    }

    private void sendPrivateMessage(String playerId, Map<String, Object> payload) {
        try {
            messagingTemplate.convertAndSend("/topic/private/" + playerId, payload);
        } catch (Exception e) {
            log.error("Private msg fail", e);
        }
    }
}
