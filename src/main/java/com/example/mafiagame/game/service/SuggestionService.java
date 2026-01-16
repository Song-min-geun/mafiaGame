package com.example.mafiagame.game.service;

import com.example.mafiagame.game.domain.GamePhase;
import com.example.mafiagame.game.domain.PlayerRole;
import com.example.mafiagame.game.dto.request.SuggestionsRequestDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 채팅 추천 문구 서비스
 * - 역할/페이즈별 추천 문구 관리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SuggestionService {

    private final StringRedisTemplate stringRedisTemplate;

    private static final String SUGGESTION_PREFIX = "suggestion:role:";

    /**
     * 역할별/페이즈별 기본 추천 문구 초기화 (서버 시작 시 1회 호출)
     */
    public void initAllSuggestions() {
        // ==================== 밤 액션 (역할별) ====================

        // 마피아 - 밤 액션
        initSuggestions(new SuggestionsRequestDto(PlayerRole.MAFIA, GamePhase.NIGHT_ACTION), List.of(
                "누구 죽일까요?",
                "1번 어때요?",
                "2번 죽이죠",
                "3번 수상해요",
                "의사 같은 사람 죽여요",
                "경찰 먼저 없애요",
                "조용한 사람 노려요",
                "말 많은 사람 죽여요"));

        // ==================== 낮 토론 ====================
        List<String> dayDiscussionSuggestions = List.of(
                "경찰 조사 결과 누구야??",
                "누가 수상해요?",
                "어젯밤에 뭐 했어요?",
                "저는 시민이에요",
                "투표하기 전에 얘기 좀 해요");

        for (PlayerRole role : PlayerRole.values()) {
            initSuggestions(new SuggestionsRequestDto(role, GamePhase.DAY_DISCUSSION), dayDiscussionSuggestions);
        }

        // ==================== 낮 투표 ====================
        List<String> dayVotingSuggestions = List.of(
                "1번 투표해요",
                "2번 수상해요",
                "3번 찍어요",
                "스킵할까요?");

        for (PlayerRole role : PlayerRole.values()) {
            initSuggestions(new SuggestionsRequestDto(role, GamePhase.DAY_VOTING), dayVotingSuggestions);
        }

        log.info("채팅 추천 문구 초기화 완료");
    }

    /**
     * 특정 역할/페이즈에 대한 추천 문구 저장
     */
    public void initSuggestions(SuggestionsRequestDto dto, List<String> suggestions) {
        String key = buildSuggestionKey(dto.role(), dto.phase());

        // 기존 키가 있으면 건너뜀 (이미 초기화됨)
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            return;
        }

        for (String suggestion : suggestions) {
            stringRedisTemplate.opsForList().rightPush(key, suggestion);
        }

        log.debug("추천 문구 초기화: role={}, phase={}, count={}", dto.role(), dto.phase(), suggestions.size());
    }

    /**
     * 역할과 페이즈에 따른 추천 문구 조회
     */
    public List<String> getSuggestions(PlayerRole role, GamePhase phase) {
        String key = buildSuggestionKey(role, phase);
        return stringRedisTemplate.opsForList().range(key, 0, -1);
    }

    /**
     * Redis 키 생성: suggestion:role:{role}:phase:{phase}
     */
    private String buildSuggestionKey(PlayerRole role, GamePhase phase) {
        return SUGGESTION_PREFIX + role.name() + ":phase:" + phase.name();
    }
}
