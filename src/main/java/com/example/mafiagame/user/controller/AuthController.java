package com.example.mafiagame.user.controller;

import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.global.jwt.RefreshTokenService;
import com.example.mafiagame.user.dto.request.RefreshTokenRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    /**
     * Refresh Token으로 새로운 Access Token 발급
     */
    @PostMapping("/refresh")
    public ResponseEntity<?> refreshToken(@RequestBody RefreshTokenRequest request) {
        try {
            String refreshToken = request.refreshToken();

            // Refresh Token에서 username 추출
            String username = jwtUtil.getUsernameFromToken(refreshToken);

            // Redis에 저장된 토큰과 비교
            if (!refreshTokenService.validateRefreshToken(username, refreshToken)) {
                return ResponseEntity.status(401).body(Map.of(
                        "success", false,
                        "message", "유효하지 않은 Refresh Token입니다."));
            }

            // 새 Access Token 발급
            String newAccessToken = jwtUtil.generateAccessToken(username);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "data", Map.of("accessToken", newAccessToken),
                    "message", "토큰이 갱신되었습니다."));

        } catch (Exception e) {
            return ResponseEntity.status(401).body(Map.of(
                    "success", false,
                    "message", "토큰 갱신에 실패했습니다: " + e.getMessage()));
        }
    }

    /**
     * 로그아웃 (Refresh Token 삭제)
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(@RequestHeader("Authorization") String authHeader) {
        try {
            String token = authHeader.substring(7);
            String username = jwtUtil.getUsernameFromToken(token);
            refreshTokenService.deleteRefreshToken(username);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "로그아웃되었습니다."));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "로그아웃되었습니다."));
        }
    }
}
