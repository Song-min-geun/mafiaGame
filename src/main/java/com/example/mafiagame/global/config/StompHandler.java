package com.example.mafiagame.global.config;

import com.example.mafiagame.global.jwt.JwtUtil;
import com.example.mafiagame.user.service.MyUserDetailsService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
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
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            try {
                String authHeader = accessor.getFirstNativeHeader("Authorization");
                if (authHeader != null && authHeader.startsWith("Bearer ")) {
                    String token = authHeader.substring(7);
                    String username = jwtUtil.getUsernameFromToken(token);
                    if (StringUtils.hasText(username)) {
                        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
                        if (jwtUtil.validateToken(token, userDetails)) {
                            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());

                            accessor.setUser(authentication);
                            accessor.setLeaveMutable(true);
                            Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                            if (sessionAttributes != null) {
                                sessionAttributes.put("user", authentication);
                                sessionAttributes.put("userId", username);
                            }
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
        // String sessionId = headerAccessor.getSessionId();
        // log.info("세션 ID: {}", sessionId);

        var principal = headerAccessor.getUser();
        if (principal != null) {
            String username = principal.getName();
            // log.info("연결된 사용자: {}", username);

            // 사용자 등록 상태 확인
            SimpUserRegistry userRegistry = getSimpUserRegistry();
            if (userRegistry != null) {
                SimpUser user = userRegistry.getUser(username);
                if (user == null) {
                    log.warn("사용자 등록되지 않음: {}", username);
                }
            }
        }
    }

    @EventListener
    public void handleSessionConnected(org.springframework.web.socket.messaging.SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        if (accessor.getUser() == null) {
            log.error("CRITICAL: SessionConnectedEvent에 사용자 정보가 없습니다! StompHandler가 제대로 동작하지 않았거나 헤더가 손실되었습니다.");
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        if (headerAccessor.getUser() != null) {
        }
    }
}
