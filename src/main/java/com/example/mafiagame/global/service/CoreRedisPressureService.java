package com.example.mafiagame.global.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Properties;

@Service
@Slf4j
public class CoreRedisPressureService {

    private final StringRedisTemplate coreStringRedisTemplate;
    private final double corePressureThreshold;
    private final boolean forceDbFallback;

    public CoreRedisPressureService(
            @Qualifier("coreStringRedisTemplate") StringRedisTemplate coreStringRedisTemplate,
            @Value("${aux.lookup.core-pressure-threshold:0.85}") double corePressureThreshold,
            @Value("${aux.lookup.force-db-fallback:false}") boolean forceDbFallback) {
        this.coreStringRedisTemplate = coreStringRedisTemplate;
        this.corePressureThreshold = corePressureThreshold;
        this.forceDbFallback = forceDbFallback;
    }

    public boolean shouldUseDbFallback() {
        return forceDbFallback || isCoreRedisUnderPressure();
    }

    public boolean isUnderPressure() {
        return isCoreRedisUnderPressure();
    }

    public boolean isCoreRedisUnderPressure() {
        try {
            Properties memoryInfo = coreStringRedisTemplate.execute(
                    (RedisCallback<Properties>) connection -> connection.serverCommands().info("memory"));
            if (memoryInfo == null) {
                return false;
            }

            long usedMemory = parseLong(memoryInfo.getProperty("used_memory"));
            long maxMemory = parseLong(memoryInfo.getProperty("maxmemory"));
            if (maxMemory <= 0) {
                return false;
            }

            return (double) usedMemory / maxMemory >= corePressureThreshold;
        } catch (RuntimeException e) {
            log.warn("Core Redis memory pressure check failed: {}", e.getMessage());
            return false;
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }
}
