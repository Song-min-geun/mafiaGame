package com.example.mafiagame.global.config;

import com.example.mafiagame.global.jwt.JwtRequestFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtRequestFilter jwtRequestFilter;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration)
            throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // csrf 보안 비활성화
                .csrf(AbstractHttpConfigurer::disable)
                // JWT를 사용하므로 세션 관리는 STATELESS로 설정
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 요청에 대한 접근 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 정적 리소스 허용
                        .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/webjars/**", "/favicon.ico")
                        .permitAll()
                        .requestMatchers("/ws/**").permitAll()
                        // H2 콘솔 허용 (개발용)
                        .requestMatchers("/h2-console/**").permitAll()
                        // API 엔드포인트 중 인증 불필요한 것들
                        .requestMatchers("/api/users/register", "/api/users/login").permitAll()
                        // 채팅 API 엔드포인트는 인증 필요
                        .requestMatchers("/api/chat/**").authenticated()
                        // 나머지 모든 요청은 인증 필요
                        .anyRequest().authenticated())
                .headers(headers -> headers.frameOptions(HeadersConfigurer.FrameOptionsConfig::disable)); // 같은 출처의 페이지만
                                                                                                          // 허용 (또는
                                                                                                          // .disable());

        // 직접 만든 JwtRequestFilter를 UsernamePasswordAuthenticationFilter 앞에 추가
        http.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
