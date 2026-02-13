package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.state.GamePlayerState;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.GameStatus;
import com.example.mafiagame.game.domain.state.PlayerRole;
import com.example.mafiagame.game.domain.state.Team;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.game.strategy.NightActionResult;
import com.example.mafiagame.game.strategy.RoleActionFactory;
import com.example.mafiagame.game.strategy.RoleActionStrategy;
import com.example.mafiagame.chat.service.WebSocketMessageBroadcaster;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 페이즈 종료 시 결과 처리를 담당하는 서비스
 * State Pattern의 onExit()에서 콜백으로 호출됩니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PhaseResultProcessor {

    private final GameStateRepository gameStateRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final WebSocketMessageBroadcaster messageBroadcaster;
    private final RoleActionFactory roleActionFactory;

    private static final String VOTE_KEY_PREFIX = "game:votes:";
    private static final String FINAL_VOTE_KEY_PREFIX = "game:finalvotes:";
    private static final String NIGHT_ACTION_KEY_PREFIX = "game:nightactions:";

    // ==================== 페이즈 결과 처리 ====================

    /**
     * DAY_DISCUSSION 종료 시: 투표 시간 연장 리스트 초기화
     */
    public void flushExtendVotingList(GameState gameState) {
        gameState.getVotingTimeExtensionsUsed().clear();
        gameStateRepository.save(gameState);
    }

    /**
     * DAY_VOTING 종료 시: 투표 집계 및 최다 득표자 결정
     */
    public void processDayVoting(GameState gameState) {
        syncVotesFromRedis(gameState);

        List<String> topVotedIds = gameState.getTopVotedPlayerIds();
        if (topVotedIds.size() != 1) {
            sendSystemMessage(gameState.getRoomId(), "투표가 무효 처리되어 밤으로 넘어갑니다.");
            gameState.setVotedPlayerId(null);
        } else {
            String votedId = topVotedIds.get(0);
            gameState.setVotedPlayerId(votedId);

            GamePlayerState player = gameState.findPlayer(votedId);
            if (player != null) {
                sendSystemMessage(gameState.getRoomId(),
                        String.format("투표 결과 %s님이 최다 득표자가 되었습니다. 최후 변론을 시작합니다.", player.getPlayerName()));
            }
        }
        gameState.getVotes().clear();
        clearVotesFromRedis(gameState.getGameId());
    }

    /**
     * DAY_FINAL_VOTING 종료 시: 찬반 투표 집계 및 처형 처리
     */
    public void processFinalVoting(GameState gameState) {
        syncFinalVotesFromRedis(gameState);

        long agree = gameState.getFinalVotes().values().stream()
                .filter(vote -> "AGREE".equals(vote)).count();
        long disagree = gameState.getFinalVotes().values().stream()
                .filter(vote -> "DISAGREE".equals(vote)).count();

        if (agree > disagree) {
            GamePlayerState player = gameState.findPlayer(gameState.getVotedPlayerId());
            if (player != null) {
                player.setAlive(false);
                sendSystemMessage(gameState.getRoomId(),
                        String.format("최종 투표 결과, %s님이 처형되었습니다.", player.getPlayerName()));
            }
        }
        clearFinalVotesFromRedis(gameState.getGameId());
        checkGameEnd(gameState);
    }

    /**
     * NIGHT_ACTION 종료 시: 밤 행동 결과 처리
     */
    public void processNight(GameState gameState) {
        syncNightActionsFromRedis(gameState);
        processNightActions(gameState);
        clearNightActionsFromRedis(gameState.getGameId());
        checkGameEnd(gameState);
    }

    // ==================== 밤 행동 결과 계산 ====================

    private void processNightActions(GameState gameState) {
        List<NightActionResult> results = new ArrayList<>();

        for (Map.Entry<String, String> action : gameState.getNightActions().entrySet()) {
            String actorId = action.getKey();
            String targetId = action.getValue();

            GamePlayerState actor = gameState.findPlayer(actorId);
            GamePlayerState target = gameState.findPlayer(targetId);

            if (actor == null || target == null)
                continue;

            RoleActionStrategy strategy = roleActionFactory.getStrategy(actor.getRole());
            if (strategy != null) {
                NightActionResult result = strategy.execute(gameState, actor, target);
                results.add(result);

                if (actor.getRole() == PlayerRole.POLICE) {
                    sendPoliceInvestigationResult(actor, target);
                }
            }
        }

        // 마피아 공격 결과 계산
        String mafiaTargetId = results.stream()
                .filter(r -> r.getActorRole() == PlayerRole.MAFIA)
                .collect(Collectors.groupingBy(NightActionResult::getTargetId, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        // 의사 보호 대상 확인
        String doctorTargetId = results.stream()
                .filter(r -> r.getActorRole() == PlayerRole.DOCTOR)
                .map(NightActionResult::getTargetId)
                .findFirst()
                .orElse(null);

        // 결과 처리
        if (mafiaTargetId != null && !mafiaTargetId.equals(doctorTargetId)) {
            GamePlayerState killed = gameState.findPlayer(mafiaTargetId);
            if (killed != null) {
                killed.setAlive(false);
                sendSystemMessage(gameState.getRoomId(), "지난 밤, " + killed.getPlayerName() + "님이 마피아의 공격으로 사망했습니다.");
            }
        } else {
            sendSystemMessage(gameState.getRoomId(), "지난 밤, 아무 일도 일어나지 않았습니다.");
        }
    }

    // ==================== 게임 종료 체크 ====================

    /**
     * 게임 종료 조건 확인 (마피아 >= 시민 or 마피아 == 0)
     * 종료 시 GameState.status를 ENDED로 변경
     */
    public boolean checkGameEnd(GameState gameState) {
        Team winnerTeam = gameState.checkWinner();
        if (winnerTeam != null) {
            gameState.setStatus(GameStatus.ENDED);
            return true;
        }
        return false;
    }

    /**
     * 게임 종료 시 승리 팀 반환 (null이면 아직 진행 중)
     */
    public Team getWinnerIfGameEnded(GameState gameState) {
        return gameState.checkWinner();
    }

    // ==================== Redis 동기화 ====================

    private void syncVotesFromRedis(GameState gameState) {
        String votesKey = VOTE_KEY_PREFIX + gameState.getGameId();
        try {
            var votes = stringRedisTemplate.opsForHash().entries(votesKey);
            gameState.getVotes().clear();
            votes.forEach((voterId, targetId) -> gameState.getVotes().put((String) voterId, (String) targetId));
            log.debug("[동기화] 투표 {}건 로드: gameId={}", votes.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 투표 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    private void syncFinalVotesFromRedis(GameState gameState) {
        String finalVotesKey = FINAL_VOTE_KEY_PREFIX + gameState.getGameId();
        try {
            var votes = stringRedisTemplate.opsForHash().entries(finalVotesKey);
            gameState.getFinalVotes().clear();
            votes.forEach(
                    (voterId, voteChoice) -> gameState.getFinalVotes().put((String) voterId, (String) voteChoice));
            log.debug("[동기화] 최종투표 {}건 로드: gameId={}", votes.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 최종투표 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    private void syncNightActionsFromRedis(GameState gameState) {
        String nightActionsKey = NIGHT_ACTION_KEY_PREFIX + gameState.getGameId();
        try {
            var actions = stringRedisTemplate.opsForHash().entries(nightActionsKey);
            gameState.getNightActions().clear();
            actions.forEach(
                    (actorId, targetId) -> gameState.getNightActions().put((String) actorId, (String) targetId));
            log.debug("[동기화] 밤행동 {}건 로드: gameId={}", actions.size(), gameState.getGameId());
        } catch (Exception e) {
            log.error("[동기화] 밤행동 로드 실패: gameId={}", gameState.getGameId(), e);
        }
    }

    private void clearVotesFromRedis(String gameId) {
        stringRedisTemplate.delete(VOTE_KEY_PREFIX + gameId);
    }

    private void clearFinalVotesFromRedis(String gameId) {
        stringRedisTemplate.delete(FINAL_VOTE_KEY_PREFIX + gameId);
    }

    private void clearNightActionsFromRedis(String gameId) {
        stringRedisTemplate.delete(NIGHT_ACTION_KEY_PREFIX + gameId);
    }

    // ==================== 메시지 전송 헬퍼 ====================

    private void sendSystemMessage(String roomId, String content) {
        messageBroadcaster.sendSystemMessage(roomId, content);
    }

    private void sendPoliceInvestigationResult(GamePlayerState police, GamePlayerState target) {
        log.info("[경찰조사] 결과 전송: police={}, target={}, isMafia={}",
                police.getPlayerId(), target.getPlayerName(), target.getRole() == PlayerRole.MAFIA);
        messageBroadcaster.sendToUser(police.getPlayerId(), Map.of(
                "type", "PRIVATE_MESSAGE",
                "messageType", "POLICE_INVESTIGATION",
                "content", String.format("경찰 조사 결과: %s님은 [ %s ] 입니다.",
                        target.getPlayerName(), target.getRole() == PlayerRole.MAFIA ? "마피아" : "시민")));
    }
}
