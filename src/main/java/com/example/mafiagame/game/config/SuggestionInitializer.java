package com.example.mafiagame.game.config;

import com.example.mafiagame.game.service.GameService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class SuggestionInitializer {

    private final GameService gameService;

    @Bean
    public ApplicationRunner initSuggestions() {
        return args -> {
            log.info("서버 시작: 채팅 추천 문구 초기화 중...");
            gameService.initAllSuggestions();
        };
    }
}
