package com.example.mafiagame.global.config;

import java.security.Principal;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.example.mafiagame.global.jwt.JwtUtil;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompHandler implements ChannelInterceptor {

    private final JwtUtil jwtUtil;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            log.info("=== WebSocket CONNECT 요청 감지 ===");
            
            // 헤더에서 Authorization 토큰 추출
            String authHeader = accessor.getFirstNativeHeader("Authorization");
            log.info("Authorization 헤더: {}", authHeader);
            
            // 모든 헤더 정보 로깅
            log.info("모든 헤더 정보:");
            log.info("accept-version: {}", accessor.getFirstNativeHeader("accept-version"));
            log.info("heart-beat: {}", accessor.getFirstNativeHeader("heart-beat"));
            log.info("Authorization: {}", accessor.getFirstNativeHeader("Authorization"));

            if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                log.info("JWT 토큰 추출: {} (길이: {}자)", token.substring(0, Math.min(token.length(), 50)) + (token.length() > 50 ? "..." : ""), token.length());
                
                // ❗ 추가: JWT 토큰 구조 분석
                String[] tokenParts = token.split("\\.");
                if (tokenParts.length == 3) {
                    log.info("JWT 토큰 구조: Header.Payload.Signature (올바름)");
                    log.info("Header 길이: {}자, Payload 길이: {}자, Signature 길이: {}자", 
                        tokenParts[0].length(), tokenParts[1].length(), tokenParts[2].length());
                } else {
                    log.error("JWT 토큰 구조가 올바르지 않음: {}개 부분", tokenParts.length);
                }

                try {
                    log.info("JWT 토큰 검증 시작...");
                    String username = jwtUtil.getUsernameFromToken(token);
                    log.info("JWT에서 추출된 username: {}", username);
                    
                    // ❗ 추가: 토큰 만료 여부 확인
                    try {
                        if (jwtUtil.isTokenExpired(token)) {
                            log.error("JWT 토큰이 만료되었습니다!");
                            return null;
                        }
                        log.info("JWT 토큰 유효성 검증 성공 (만료되지 않음)");
                    } catch (Exception e) {
                        log.error("JWT 토큰 만료 확인 중 오류: {}", e.getMessage());
                        return null;
                    }

                    if (StringUtils.hasText(username)) {
                        // ❗ 중요: 강력한 Principal 설정
                        // 커스텀 Principal 객체 생성 (Spring Security 호환)
                        Principal customPrincipal = new Principal() {
                            @Override
                            public String getName() {
                                return username;
                            }
                            
                            @Override
                            public String toString() {
                                return "CustomPrincipal{username='" + username + "'}";
                            }
                        };
                        
                        // accessor에 user를 설정하여 웹소켓 세션에 인증 정보를 저장
                        accessor.setUser(customPrincipal);
                        
                        // ❗ 추가: Principal 설정 확인
                        Principal setPrincipal = accessor.getUser();
                        log.info("Principal 설정 확인: {}", setPrincipal);
                        log.info("Principal name: {}", setPrincipal != null ? setPrincipal.getName() : "NULL");
                        
                        log.info("Successfully authenticated user '{}' with custom Principal for WebSocket session.", username);
                        
                        // ❗ 추가: 최종 Principal 설정 상태 확인
                        Principal finalPrincipal = accessor.getUser();
                        if (finalPrincipal != null) {
                            log.info("✅ WebSocket 연결 성공! Principal 설정 완료: {}", finalPrincipal.getName());
                        } else {
                            log.error("❌ WebSocket 연결 실패! Principal 설정 실패!");
                            return null;  // Principal 설정 실패 시 연결 거부
                        }
                    } else {
                        log.error("Invalid username from JWT token");
                        return null;  // 연결 거부
                    }
                } catch (Exception e) {
                    log.error("JWT verification failed: {}", e.getMessage());
                    return null;  // 연결 거부
                }
            } else {
                log.error("Authorization header not found or invalid");
                return null;  // 연결 거부
            }
        }
        return message;
    }
}
