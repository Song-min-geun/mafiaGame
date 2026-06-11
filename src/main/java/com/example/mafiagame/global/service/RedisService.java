package com.example.mafiagame.global.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.game.domain.entity.Game;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisTemplate<String, ChatRoom> chatRoomRedisTemplate;
    private final RedisTemplate<String, Game> gameRedisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    // Redis 키 상수
    private static final String CHAT_ROOM_PREFIX = "chatroom:";
    private static final String GAME_PREFIX = "game:";
    private static final String USER_SESSION_PREFIX = "user_session:";
    private static final String ROOM_LIST_KEY = "room_list";
    private static final String ACTIVE_GAMES_KEY = "active_games";

    private static final Duration CHAT_ROOM_TTL = Duration.ofHours(24);
    private static final Duration GAME_TTL = Duration.ofHours(24);
    private static final Duration USER_SESSION_TTL = Duration.ofHours(24);

    // ========== 채팅방 관련 ==========

    /**
     * 채팅방 저장 (호출자가 Redisson Lock 보유)
     */
    public void saveChatRoom(ChatRoom chatRoom) {
        String key = CHAT_ROOM_PREFIX + chatRoom.getRoomId();
        String payload = serializeChatRoom(chatRoom);
        stringRedisTemplate.opsForValue().set(key, payload, CHAT_ROOM_TTL);
        stringRedisTemplate.opsForSet().add(ROOM_LIST_KEY, chatRoom.getRoomId());
    }

    /**
     * 채팅방 조회
     */
    public ChatRoom getChatRoom(String roomId) {
        String key = CHAT_ROOM_PREFIX + roomId;
        return chatRoomRedisTemplate.opsForValue().get(key);
    }

    /**
     * 채팅방 삭제 (호출자가 Redisson Lock 보유)
     */
    public void deleteChatRoom(String roomId) {
        String key = CHAT_ROOM_PREFIX + roomId;
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForSet().remove(ROOM_LIST_KEY, roomId);
    }

    /**
     * 모든 채팅방 ID 조회
     */
    public Set<String> getAllRoomIds() {
        Set<String> rawMembers = stringRedisTemplate.opsForSet().members(ROOM_LIST_KEY);
        if (rawMembers == null) {
            return new HashSet<>();
        }
        return new HashSet<>(rawMembers);
    }

    // ========== 게임 관련 ==========

    /**
     * 게임 저장 (호출자가 Redisson Lock 보유)
     */
    public void saveGame(Game game) {
        String key = GAME_PREFIX + game.getGameId();
        String payload = serializeGame(game);
        stringRedisTemplate.opsForValue().set(key, payload, GAME_TTL);
        stringRedisTemplate.opsForSet().add(ACTIVE_GAMES_KEY, game.getGameId());
    }

    /**
     * 게임 조회
     */
    public Game getGame(String gameId) {
        String key = GAME_PREFIX + gameId;
        return gameRedisTemplate.opsForValue().get(key);
    }

    /**
     * 게임 삭제 (호출자가 Redisson Lock 보유)
     */
    public void deleteGame(String gameId) {
        String key = GAME_PREFIX + gameId;
        stringRedisTemplate.delete(key);
        stringRedisTemplate.opsForSet().remove(ACTIVE_GAMES_KEY, gameId);
    }

    /**
     * 모든 활성 게임 ID 조회
     */
    public Set<String> getAllActiveGameIds() {
        Set<String> rawMembers = stringRedisTemplate.opsForSet().members(ACTIVE_GAMES_KEY);
        if (rawMembers == null) {
            return new HashSet<>();
        }
        return new HashSet<>(rawMembers);
    }

    /**
     * 방 ID로 게임 조회
     */
    public Game getGameByRoomId(String roomId) {
        Set<String> gameIds = getAllActiveGameIds();
        for (String gameId : gameIds) {
            Game game = getGame(gameId);
            if (game != null && roomId.equals(game.getRoomId())) {
                return game;
            }
        }
        return null;
    }

    // ========== 사용자 세션 관련 ==========

    /**
     * 사용자 세션 저장 (호출자가 Redisson Lock 보유)
     */
    public void saveUserSession(String userId, String roomId, String gameId) {
        String key = USER_SESSION_PREFIX + userId;
        String safeRoomId = nullToEmpty(roomId);
        String safeGameId = nullToEmpty(gameId);
        stringRedisTemplate.opsForHash().putAll(key, Map.of("roomId", safeRoomId, "gameId", safeGameId));
        stringRedisTemplate.expire(key, USER_SESSION_TTL);
    }

    /**
     * 사용자 세션 조회
     */
    public String getUserRoomId(String userId) {
        String key = USER_SESSION_PREFIX + userId;
        Object roomId = stringRedisTemplate.opsForHash().get(key, "roomId");
        return emptyToNull(roomId != null ? roomId.toString() : null);
    }

    /**
     * 사용자 게임 ID 조회
     */
    public String getUserGameId(String userId) {
        String key = USER_SESSION_PREFIX + userId;
        Object gameId = stringRedisTemplate.opsForHash().get(key, "gameId");
        return emptyToNull(gameId != null ? gameId.toString() : null);
    }

    /**
     * 사용자 세션 삭제
     */
    public void deleteUserSession(String userId) {
        String key = USER_SESSION_PREFIX + userId;
        redisTemplate.delete(key);

        // log.info("사용자 세션 삭제: {}", userId);
    }

    // ========== 유틸리티 ==========

    /**
     * 키 존재 여부 확인
     */
    public boolean exists(String key) {
        return redisTemplate.hasKey(key);
    }

    /**
     * TTL 조회
     */
    public Long getTtl(String key) {
        return redisTemplate.getExpire(key, TimeUnit.SECONDS);
    }

    /**
     * 모든 데이터 삭제 (개발용)
     */
    public void flushAll() {
        var connectionFactory = redisTemplate.getConnectionFactory();
        if (connectionFactory != null) {
            connectionFactory.getConnection().serverCommands().flushAll();
            log.warn("Redis 모든 데이터 삭제됨");
        }
    }

    /**
     * 서버 시작 시 게임 및 채팅방 데이터 초기화
     * (사용자 세션 user_session:* 은 유지)
     */
    public void clearAllGameData() {
        int deletedCount = 0;

        try {
            // 1. 관리 집합 키 삭제
            redisTemplate.delete(ROOM_LIST_KEY);
            redisTemplate.delete(ACTIVE_GAMES_KEY);
            deletedCount += 2;

            // 2. 게임 데이터 삭제 (gameRedisTemplate 사용)
            Set<String> gameKeys = gameRedisTemplate.keys(GAME_PREFIX + "*");
            if (gameKeys != null && !gameKeys.isEmpty()) {
                gameRedisTemplate.delete(gameKeys);
                deletedCount += gameKeys.size();
                log.info("게임 데이터 {}개 삭제", gameKeys.size());
            }

            // 3. 채팅방 데이터 삭제 (chatRoomRedisTemplate 사용)
            Set<String> chatRoomKeys = chatRoomRedisTemplate.keys(CHAT_ROOM_PREFIX + "*");
            if (chatRoomKeys != null && !chatRoomKeys.isEmpty()) {
                chatRoomRedisTemplate.delete(chatRoomKeys);
                deletedCount += chatRoomKeys.size();
                log.info("채팅방 데이터 {}개 삭제", chatRoomKeys.size());
            }

            // 주의: USER_SESSION_PREFIX 는 삭제하지 않음 (로그인 유지)

            log.info("서버 시작 초기화: Redis 게임 및 채팅방 데이터 총 {}개 삭제됨", deletedCount);

        } catch (Exception e) {
            log.error("Redis 키 검색/삭제 중 오류 발생", e);
        }
    }

    // ========== Private 유틸리티 메서드 ==========

    private String serializeChatRoom(ChatRoom chatRoom) {
        try {
            return objectMapper.writeValueAsString(chatRoom);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("ChatRoom 직렬화 실패: " + chatRoom.getRoomId(), e);
        }
    }

    private String serializeGame(Game game) {
        try {
            return objectMapper.writeValueAsString(game);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Game 직렬화 실패: " + game.getGameId(), e);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String emptyToNull(String value) {
        return (value == null || value.isEmpty()) ? null : value;
    }
}
