package com.example.mafiagame.global.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final StringRedisTemplate stringRedisTemplate;
    private final JwtUtil jwtUtil;

    private static final String REFRESH_TOKEN_PREFIX = "refresh_token:";

    /**
     * Refresh Token을 Redis에 저장
     */
    public void saveRefreshToken(String username, String refreshToken) {
        String key = REFRESH_TOKEN_PREFIX + username;
        long expirationMs = jwtUtil.getRefreshExpiration();
        stringRedisTemplate.opsForValue().set(key, refreshToken, expirationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 저장된 Refresh Token 조회
     */
    public String getRefreshToken(String username) {
        String key = REFRESH_TOKEN_PREFIX + username;
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * Refresh Token 검증
     */
    public boolean validateRefreshToken(String username, String refreshToken) {
        String storedToken = getRefreshToken(username);
        return storedToken != null && storedToken.equals(refreshToken);
    }

    /**
     * Refresh Token 삭제 (로그아웃 시)
     */
    public void deleteRefreshToken(String username) {
        String key = REFRESH_TOKEN_PREFIX + username;
        stringRedisTemplate.delete(key);
    }
}
