package com.example.mafiagame.global.config;

import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.user.service.MyUserDetailsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.context.event.EventListener;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.context.ApplicationContext;

import java.util.Map;

import org.springframework.messaging.support.MessageBuilder;

@Component
@RequiredArgsConstructor
@Slf4j
public class StompHandler implements ChannelInterceptor {

    private final JwtUtil jwtUtil;
    private final MyUserDetailsService userDetailsService;
    private final ApplicationContext applicationContext;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            try {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    String username = jwtUtil.getUsernameFromToken(token);
                    if (StringUtils.hasText(username)) {
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                        if (jwtUtil.validateToken(token, userDetails)) {
                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());

                            // Set the user on the session regardless of session attributes
                            accessor.setUser(authentication);
                            accessor.setLeaveMutable(true); // Ensure headers remain mutable for downstream processing
                            // log.info(
                            // "StompHandler: WebSocket 세션에 사용자 '{}' 인증 완료! Principal 이름: '{}', UserDetails
                            // username: '{}'",
                            // username, authentication.getName(), userDetails.getUsername());

                            // 사용자 식별자를 세션 속성에 저장 (convertAndSendToUser에서 사용)
                            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                            if (sessionAttributes != null) {
                                sessionAttributes.put("user", authentication);
                                sessionAttributes.put("userId", username); // userLoginId 저장
                                // log.info("StompHandler: 세션 속성에 userId '{}' 저장됨", username);
                            }

                            // 인증 정보가 담긴 새로운 메시지 반환 (중요!)
                            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
                        } else {
                            log.error("StompHandler: 토큰 검증 실패! username={}, token={}", username, token);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("StompHandler: 토큰 인증 처리 중 예외 발생!", e);
            }
        }
        return message;
    }

    private SimpUserRegistry getSimpUserRegistry() {
        try {
            return applicationContext.getBean(SimpUserRegistry.class);
        } catch (Exception e) {
            log.warn("SimpUserRegistry를 가져올 수 없습니다: {}", e.getMessage());
            return null;
        }
    }

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectEvent event) {
        // log.info("WebSocket 연결 이벤트 발생");
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = headerAccessor.getSessionId();
        // log.info("세션 ID: {}", sessionId);

        if (headerAccessor.getUser() != null) {
            String username = headerAccessor.getUser().getName();
            // log.info("연결된 사용자: {}", username);

            // 사용자 등록 상태 확인
            SimpUserRegistry userRegistry = getSimpUserRegistry();
            if (userRegistry != null) {
                SimpUser user = userRegistry.getUser(username);
                if (user != null) {
                    // log.info("사용자 등록 확인: {} (세션 수: {})", username, user.getSessions().size());
                } else {
                    log.warn("사용자 등록되지 않음: {}", username);
                }
            }
        }
    }

    @EventListener
    public void handleSessionConnected(org.springframework.web.socket.messaging.SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        // log.info("SessionConnectedEvent 발생: user={}, sessionId={}",
        // accessor.getUser(), accessor.getSessionId());
        if (accessor.getUser() == null) {
            log.error("CRITICAL: SessionConnectedEvent에 사용자 정보가 없습니다! StompHandler가 제대로 동작하지 않았거나 헤더가 손실되었습니다.");
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        // log.info("WebSocket 연결 해제 이벤트 발생");
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        if (headerAccessor.getUser() != null) {
            // log.info("연결 해제된 사용자: {}", headerAccessor.getUser().getName());
        }
    }
}
