package com.example.mafiagame.global.config;

import com.example.mafiagame.global.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RedisDataInitializer implements ApplicationRunner {

    private final RedisService redisService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("▶ Redis 데이터 초기화 시작 (게임 및 채팅방 데이터)...");
        try {
            redisService.clearAllGameData();
            log.info("▶ Redis 데이터 초기화 완료");
        } catch (Exception e) {
            log.error("▶ Redis 데이터 초기화 중 오류 발생", e);
        }
    }
}
