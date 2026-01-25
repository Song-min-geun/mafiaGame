package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.state.GamePhase;
import com.example.mafiagame.game.domain.state.GameState;
import com.example.mafiagame.game.domain.state.PlayerRole;
import com.example.mafiagame.game.repository.GameStateRepository;
import com.example.mafiagame.global.client.GeminiApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 채팅 추천 문구 서비스
 * - 역할/페이즈별 추천 문구 관리
 * - AI 기반 실시간/문맥 기반 추천 생성
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuggestionService {

    private final StringRedisTemplate stringRedisTemplate;
    private final GeminiApiClient geminiApiClient;
    private final GameStateRepository gameStateRepository;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    private static final String SUGGESTION_PREFIX = "suggestion:role:";
    private static final String GAME_SUGGESTION_PREFIX = "suggestion:game:";
    private static final String CHAT_LOG_PREFIX = "chat:logs:";

    /**
     * 역할별/페이즈별 기본 추천 문구 초기화 (서버 시작 시 1회 호출)
     */
    public void initAllSuggestions() {
        // ==================== 밤 액션 (역할별) ====================
        initSuggestions(PlayerRole.MAFIA, GamePhase.NIGHT_ACTION, List.of(
                "누구 죽일까요?", "1번 어때요?", "2번 죽이죠", "3번 수상해요",
                "의사 같은 사람 죽여요", "경찰 먼저 없애요", "조용한 사람 노려요", "말 많은 사람 죽여요"));

        // ==================== 낮 토론 ====================
        List<String> dayDiscussionSuggestions = List.of(
                "경찰 조사 결과 누구야??", "누가 수상해요?", "어젯밤에 뭐 했어요?",
                "저는 시민이에요", "투표하기 전에 얘기 좀 해요");

        for (PlayerRole role : PlayerRole.values()) {
            initSuggestions(role, GamePhase.DAY_DISCUSSION, dayDiscussionSuggestions);
        }

        // ==================== 낮 투표 ====================
        List<String> dayVotingSuggestions = List.of(
                "1번 투표해요", "2번 수상해요", "3번 찍어요", "스킵할까요?");

        for (PlayerRole role : PlayerRole.values()) {
            initSuggestions(role, GamePhase.DAY_VOTING, dayVotingSuggestions);
        }

        log.info("채팅 추천 문구 초기화 완료");
    }

    public void initSuggestions(PlayerRole role, GamePhase phase, List<String> suggestions) {
        String key = buildGlobalSuggestionKey(role, phase);

        // 기존 데이터가 있다면 삭제하고 새로 등록 (항상 최신 상태 유지)
        stringRedisTemplate.delete(key);

        log.info("추천 문구 초기화 저장: key={}, count={}", key, suggestions.size());
        for (String suggestion : suggestions) {
            stringRedisTemplate.opsForList().rightPush(key, suggestion);
        }
    }

    /**
     * 역할과 페이즈에 따른 추천 문구 조회
     */
    // 오버로딩: gameId가 있는 경우
    public List<String> getSuggestions(PlayerRole role, GamePhase phase, String gameId) {
        // DAY 페이즈에서는 역할에 관계없이 동일한 추천 제공 (CITIZEN 키 사용)
        PlayerRole effectiveRole = isDayPhase(phase) ? PlayerRole.CITIZEN : role;

        if (gameId != null) {
            String gameKey = buildGameSuggestionKey(gameId, effectiveRole, phase);
            List<String> aiSuggestions = stringRedisTemplate.opsForList().range(gameKey, 0, -1);
            if (aiSuggestions != null && !aiSuggestions.isEmpty()) {
                log.info("게임별 AI 추천(Redis) 발견: key={}, size={}", gameKey, aiSuggestions.size());
                return aiSuggestions;
            } else {
                log.info("게임별 AI 추천 없음: key={}", gameKey);
            }
        }
        return getSuggestions(effectiveRole, phase); // Fallback to global
    }

    public List<String> getSuggestions(PlayerRole role, GamePhase phase) {
        String key = buildGlobalSuggestionKey(role, phase);
        List<String> suggestions = stringRedisTemplate.opsForList().range(key, 0, -1);
        log.info("전역 기본 추천(Redis) 조회: key={}, size={}", key, suggestions != null ? suggestions.size() : "null");
        return suggestions != null ? suggestions : List.of();
    }

    // ==================== 역할별 추천 대상 설정 (확장 가능) ====================
    // 밤 페이즈에서 AI 추천을 받을 역할 목록 (마피아끼리만 대화)
    private static final java.util.Set<PlayerRole> NIGHT_SUGGESTION_ROLES = java.util.Set.of(
            PlayerRole.MAFIA
    // 향후 추가 역할: PlayerRole.WITCH, PlayerRole.CULT_LEADER 등
    );

    /**
     * [비동기] AI 문구 생성 및 캐싱
     */
    @Async
    public void generateAiSuggestionsAsync(String gameId, GamePhase phase) {
        // 1. 게임 상태 조회
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null)
            return;

        String roomId = gameState.getRoomId();

        // 2. 최근 채팅 로그 조회 (Redis 직접 조회)
        String chatLogKey = CHAT_LOG_PREFIX + roomId;
        List<String> chatLogs = stringRedisTemplate.opsForList().range(chatLogKey, 0, -1);
        String chatContext = (chatLogs != null) ? String.join("\n", chatLogs) : "";

        // 3. 페이즈별 추천 대상 역할 결정
        if (isDayPhase(phase)) {
            // DAY: 모든 역할이 동일한 추천 공유 (CITIZEN 키 사용)
            generateAndCacheForRole(gameId, phase, PlayerRole.CITIZEN, chatContext);
        } else {
            // NIGHT: 설정된 역할만 추천 생성 (현재는 MAFIA만)
            for (PlayerRole role : NIGHT_SUGGESTION_ROLES) {
                generateAndCacheForRole(gameId, phase, role, chatContext);
            }
        }
    }

    private void generateAndCacheForRole(String gameId, GamePhase phase, PlayerRole role, String chatContext) {
        log.info("AI 추천 생성 시작: gameId={}, role={}, phase={}", gameId, role, phase);
        try {
            String prompt = String.format("""
                    Context: Mafia Game (Social Deduction Game)
                    Current Phase: %s
                    Role: %s
                    Language: Korean (Casual, Chat-style)
                    Recent Chat Logs:
                    %s

                    Task: Suggest 3 short, natural chat messages for this role to say in this situation.
                    - If Mafia: Try to blend in or mislead nicely.
                    - If Police/Doctor: Act like a citizen or give subtle hints.
                    - If Citizen: Try to find the Mafia.
                    - Keep it under 20 characters per message.
                    - Return ONLY the messages, separated by specific delimiter '|||'.
                    Example: 나 진짜 시민임|||누가 마피아 같아?|||3번 좀 수상한데
                    """, phase.name(), role.name(), chatContext.isEmpty() ? "(No chat yet)" : chatContext);

            String response = geminiApiClient.generateContent(prompt);

            if (response != null && !response.isBlank()) {
                String[] suggestions = response.split("\\|\\|\\|");

                String key = buildGameSuggestionKey(gameId, role, phase);
                stringRedisTemplate.delete(key); // 기존 키 삭제

                for (String s : suggestions) {
                    if (key == null) {
                        log.error("AI 추천 키 생성 실패 (null): gameId={}, role={}, phase={}", gameId, role, phase);
                        continue;
                    }
                    if (!s.isBlank()) {
                        stringRedisTemplate.opsForList().rightPush(key, s.trim());
                    }
                }
                try {
                    stringRedisTemplate.expire(key, 1, TimeUnit.MINUTES);
                } catch (Exception e) {
                    log.error("Redis 만료 시간 설정 실패(데이터는 저장됨): key={}", key, e);
                }
                log.info("AI 추천 생성 성공 및 Redis 저장: key={}, count={}", key, suggestions.length);

                // WebSocket 알림 전송 (해당 역할을 가진 플레이어들에게만)
                notifyPlayers(gameId, role, suggestions);
            } else {
                log.warn("AI 응답이 비어있음: gameId={}, role={}", gameId, role);
            }
        } catch (Exception e) {
            log.error("AI 생성 실패", e);
        }
    }

    private void notifyPlayers(String gameId, PlayerRole role, String[] suggestions) {
        GameState gameState = gameStateRepository.findById(gameId).orElse(null);
        if (gameState == null || gameState.getPlayers() == null)
            return;

        Map<String, Object> message = Map.of(
                "type", "AI_SUGGESTION",
                "suggestions", List.of(suggestions));

        gameState.getPlayers().forEach(player -> {
            if (player.getRole() == role && player.isAlive()) {
                try {
                    // /topic/private/{userId} 로 전송
                    messagingTemplate.convertAndSend("/topic/private/" + player.getPlayerId(), message);
                } catch (Exception e) {
                    log.error("WebSocket 알림 전송 실패: userId={}", player.getPlayerId(), e);
                }
            }
        });
    }

    private String buildGlobalSuggestionKey(PlayerRole role, GamePhase phase) {
        return SUGGESTION_PREFIX + role.name() + ":phase:" + phase.name();
    }

    private String buildGameSuggestionKey(String gameId, PlayerRole role, GamePhase phase) {
        return GAME_SUGGESTION_PREFIX + gameId + ":role:" + role.name() + ":phase:" + phase.name();
    }

    private boolean isDayPhase(GamePhase phase) {
        return phase == GamePhase.DAY_DISCUSSION ||
                phase == GamePhase.DAY_VOTING ||
                phase == GamePhase.DAY_FINAL_DEFENSE ||
                phase == GamePhase.DAY_FINAL_VOTING;
    }
}
