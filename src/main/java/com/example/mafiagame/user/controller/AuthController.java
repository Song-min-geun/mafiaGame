package com.example.mafiagame.user.controller;

import com.example.mafiagame.global.dto.CommonResponse;
import com.example.mafiagame.global.error.ErrorCode;
import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.global.jwt.RefreshTokenService;
import com.example.mafiagame.user.dto.request.RefreshTokenRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    /**
     * Refresh Token으로 새로운 Access Token 발급
     */
    @PostMapping("/refresh")
    public ResponseEntity<CommonResponse<Map<String, String>>> refreshToken(
            @Valid @RequestBody RefreshTokenRequest request) {
        String refreshToken = request.refreshToken();

        if (!jwtUtil.validateToken(refreshToken)) {
            throw ErrorCode.INVALID_REFRESH_TOKEN.commonException();
        }

        String tokenType = jwtUtil.getClaimFromToken(refreshToken, claims -> claims.get("type", String.class));
        if (!"refresh".equals(tokenType)) {
            throw ErrorCode.INVALID_REFRESH_TOKEN.commonException();
        }

        String username = jwtUtil.getUsernameFromToken(refreshToken);

        if (!refreshTokenService.validateRefreshToken(username, refreshToken)) {
            throw ErrorCode.INVALID_REFRESH_TOKEN.commonException();
        }

        String newAccessToken = jwtUtil.generateAccessToken(username);
        return ResponseEntity.ok(CommonResponse.success(Map.of("accessToken", newAccessToken), "토큰이 갱신되었습니다."));
    }

    /**
     * 로그아웃 (Refresh Token 삭제)
     */
    @PostMapping("/logout")
    public ResponseEntity<CommonResponse<Void>> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            try {
                String username = jwtUtil.getUsernameFromToken(token);
                refreshTokenService.deleteRefreshToken(username);
            } catch (Exception e) {
                log.debug("Logout token parsing failed", e);
            }
        }

        // 로그아웃은 멱등성을 보장 (토큰이 없거나 만료되어도 OK)
        return ResponseEntity.ok(CommonResponse.success(null, "로그아웃되었습니다."));
    }
}
