package com.example.mafiagame.global.config;

import com.example.mafiagame.global.service.RedisService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;

@Component
@Conditional(RedisDataInitializer.EnabledCondition.class)
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

    public static class EnabledCondition implements Condition {
        private static final Set<String> ENABLED_PROFILES = Set.of("dev", "test", "local");
        private static final String ENABLED_PROPERTY = "mafiagame.redis.clear-game-data-on-startup";

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment environment = context.getEnvironment();
            boolean propertyEnabled = environment.getProperty(ENABLED_PROPERTY, Boolean.class, false);
            boolean profileEnabled = Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(ENABLED_PROFILES::contains);

            return propertyEnabled || profileEnabled;
        }
    }
}
