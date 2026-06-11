package com.example.mafiagame.global.oauth2;

import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.global.jwt.RefreshTokenService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Duration;
import java.util.Locale;

@Component
@RequiredArgsConstructor
@Slf4j
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;

    @Value("${mafiagame.oauth2.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${mafiagame.oauth2.cookie.same-site:Lax}")
    private String cookieSameSite;

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

        addTokenCookie(response, "accessToken", accessToken, Duration.ofHours(1));
        addTokenCookie(response, "refreshToken", refreshToken, Duration.ofDays(7));

        // 프론트엔드로 리다이렉트 (토큰 없이 홈으로)
        getRedirectStrategy().sendRedirect(request, response, "/");
    }

    private void addTokenCookie(HttpServletResponse response, String name, String value, Duration maxAge) {
        ResponseCookie.ResponseCookieBuilder cookieBuilder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .path("/")
                .maxAge(maxAge);

        String sameSite = normalizeSameSite(cookieSameSite);
        if (StringUtils.hasText(sameSite)) {
            cookieBuilder.sameSite(sameSite);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, cookieBuilder.build().toString());
    }

    private String normalizeSameSite(String sameSite) {
        if (!StringUtils.hasText(sameSite)) {
            return null;
        }

        return switch (sameSite.trim().toLowerCase(Locale.ROOT)) {
            case "strict" -> "Strict";
            case "none" -> "None";
            case "lax" -> "Lax";
            default -> {
                log.warn("Invalid OAuth2 cookie SameSite value '{}'. Falling back to Lax.", sameSite);
                yield "Lax";
            }
        };
    }
}
