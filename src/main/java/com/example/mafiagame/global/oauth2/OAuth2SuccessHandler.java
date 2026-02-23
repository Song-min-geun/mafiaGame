package com.example.mafiagame.global.oauth2;

import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.global.jwt.RefreshTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {

        CustomOAuth2User oAuth2User = (CustomOAuth2User) authentication.getPrincipal();
        String userLoginId = oAuth2User.getUsers().getUserLoginId();

        // JWT 토큰 생성
        String accessToken = jwtUtil.generateAccessToken(userLoginId);
        String refreshToken = jwtUtil.generateRefreshToken(userLoginId);

        // Refresh Token Redis 저장
        refreshTokenService.saveRefreshToken(userLoginId, refreshToken);

        log.info("OAuth2 로그인 성공, JWT 발급: {}", userLoginId);

        // Access Token 쿠키 설정
        Cookie accessTokenCookie = new Cookie("accessToken", accessToken);
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setSecure(false); // 개발 환경에서는 false, 운영 환경에서는 true 권장
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge(3600); // 1시간
        response.addCookie(accessTokenCookie);

        // Refresh Token 쿠키 설정
        Cookie refreshTokenCookie = new Cookie("refreshToken", refreshToken);
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(false);
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge(7 * 24 * 3600); // 7일
        response.addCookie(refreshTokenCookie);

        // 프론트엔드로 리다이렉트 (토큰 없이 홈으로)
        getRedirectStrategy().sendRedirect(request, response, "/");
    }
}
