package com.example.mafiagame.global.service;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.game.domain.entity.Game;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final RedisTemplate<String, ChatRoom> chatRoomRedisTemplate;
    private final RedisTemplate<String, Game> gameRedisTemplate;

    // Redis 키 상수
    private static final String CHAT_ROOM_PREFIX = "chatroom:";
    private static final String GAME_PREFIX = "game:";
    private static final String USER_SESSION_PREFIX = "user_session:";
    private static final String ROOM_LIST_KEY = "room_list";
    private static final String ACTIVE_GAMES_KEY = "active_games";

    // ========== 채팅방 관련 ==========

    /**
     * 채팅방 저장
     */
    public void saveChatRoom(ChatRoom chatRoom) {
        String key = CHAT_ROOM_PREFIX + chatRoom.getRoomId();
        chatRoomRedisTemplate.opsForValue().set(key, chatRoom, Duration.ofHours(24));

        // 방 목록에 추가
        redisTemplate.opsForSet().add(ROOM_LIST_KEY, chatRoom.getRoomId());

        // log.info("채팅방 Redis 저장: {}", chatRoom.getRoomId());
    }

    /**
     * 채팅방 조회
     */
    public ChatRoom getChatRoom(String roomId) {
        String key = CHAT_ROOM_PREFIX + roomId;
        return chatRoomRedisTemplate.opsForValue().get(key);
    }

    /**
     * 채팅방 삭제
     */
    public void deleteChatRoom(String roomId) {
        String key = CHAT_ROOM_PREFIX + roomId;
        chatRoomRedisTemplate.delete(key);

        // 방 목록에서 제거
        redisTemplate.opsForSet().remove(ROOM_LIST_KEY, roomId);

        // log.info("채팅방 Redis 삭제: {}", roomId);
    }

    /**
     * 모든 채팅방 ID 조회
     */
    public Set<String> getAllRoomIds() {
        Set<Object> rawMembers = redisTemplate.opsForSet().members(ROOM_LIST_KEY);
        if (rawMembers == null) {
            return new HashSet<>();
        }
        return rawMembers.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    // ========== 게임 관련 ==========

    /**
     * 게임 저장
     */
    public void saveGame(Game game) {
        String key = GAME_PREFIX + game.getGameId();
        gameRedisTemplate.opsForValue().set(key, game, Duration.ofHours(24));

        // 활성 게임 목록에 추가
        redisTemplate.opsForSet().add(ACTIVE_GAMES_KEY, game.getGameId());

        // log.info("게임 Redis 저장: {}", game.getGameId());
    }

    /**
     * 게임 조회
     */
    public Game getGame(String gameId) {
        String key = GAME_PREFIX + gameId;
        return gameRedisTemplate.opsForValue().get(key);
    }

    /**
     * 게임 삭제
     */
    public void deleteGame(String gameId) {
        String key = GAME_PREFIX + gameId;
        gameRedisTemplate.delete(key);

        // 활성 게임 목록에서 제거
        redisTemplate.opsForSet().remove(ACTIVE_GAMES_KEY, gameId);

        // log.info("게임 Redis 삭제: {}", gameId);
    }

    /**
     * 모든 활성 게임 ID 조회
     */
    public Set<String> getAllActiveGameIds() {
        Set<Object> rawMembers = redisTemplate.opsForSet().members(ACTIVE_GAMES_KEY);
        if (rawMembers == null) {
            return new HashSet<>();
        }
        return rawMembers.stream()
                .map(Object::toString)
                .collect(Collectors.toSet());
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
     * 사용자 세션 저장
     */
    public void saveUserSession(String userId, String roomId, String gameId) {
        String key = USER_SESSION_PREFIX + userId;
        redisTemplate.opsForHash().put(key, "roomId", roomId);
        redisTemplate.opsForHash().put(key, "gameId", gameId);
        redisTemplate.expire(key, Duration.ofHours(24));

        // log.info("사용자 세션 저장: {} -> roomId: {}, gameId: {}", userId, roomId, gameId);
    }

    /**
     * 사용자 세션 조회
     */
    public String getUserRoomId(String userId) {
        String key = USER_SESSION_PREFIX + userId;
        Object roomId = redisTemplate.opsForHash().get(key, "roomId");
        return roomId != null ? roomId.toString() : null;
    }

    /**
     * 사용자 게임 ID 조회
     */
    public String getUserGameId(String userId) {
        String key = USER_SESSION_PREFIX + userId;
        Object gameId = redisTemplate.opsForHash().get(key, "gameId");
        return gameId != null ? gameId.toString() : null;
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
}
