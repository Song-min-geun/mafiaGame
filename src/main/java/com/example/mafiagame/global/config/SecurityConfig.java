package com.example.mafiagame.global.config;

import com.example.mafiagame.global.jwt.JwtRequestFilter;
import com.example.mafiagame.global.oauth2.CustomOAuth2UserService;
import com.example.mafiagame.global.oauth2.OAuth2SuccessHandler;
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
        private final CustomOAuth2UserService customOAuth2UserService;
        private final OAuth2SuccessHandler oAuth2SuccessHandler;

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
                                .sessionManagement(session -> session
                                                .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                                // 요청에 대한 접근 권한 설정
                                .authorizeHttpRequests(auth -> auth
                                                // 정적 리소스 허용
                                                .requestMatchers("/", "/index.html", "/css/**", "/js/**", "/webjars/**",
                                                                "/favicon.ico")
                                                .permitAll()
                                                .requestMatchers("/ws/**").permitAll()
                                                // H2 콘솔 허용 (개발용)
                                                .requestMatchers("/h2-console/**").permitAll()
                                                // OAuth2 관련 엔드포인트 허용
                                                .requestMatchers("/oauth2/**", "/login/**", "/login/oauth2/**")
                                                .permitAll()
                                                // API 엔드포인트 중 인증 불필요한 것들
                                                .requestMatchers("/api/users/register", "/api/users/login",
                                                                "/api/games", "/api/auth/refresh")
                                                .permitAll()
                                                // 테스트용 API (개발 환경에서만 사용)
                                                .requestMatchers("/api/test/**").permitAll()
                                                // 채팅 API 엔드포인트는 인증 필요
                                                .requestMatchers("/api/chat/**").authenticated()
                                                // 나머지 모든 요청은 인증 필요
                                                .anyRequest().authenticated())
                                // OAuth2 로그인 설정
                                .oauth2Login(oauth2 -> oauth2
                                                .loginPage("/")
                                                .userInfoEndpoint(userInfo -> userInfo
                                                                .userService(customOAuth2UserService))
                                                .successHandler(oAuth2SuccessHandler))
                                .headers(headers -> headers
                                                .frameOptions(HeadersConfigurer.FrameOptionsConfig::disable));

                http.addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

                return http.build();
        }
}
