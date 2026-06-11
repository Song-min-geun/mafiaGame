package com.example.mafiagame.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Arrays;
import java.util.List;

/**
 * Redisson 설정 - 분산 락을 위한 Redis 클라이언트
 */
@Configuration
public class RedissonConfig {

    @Value("${mafiagame.redis.core.host:${REDIS_CORE_HOST:${CORE_TIMER_REDIS_HOST:${spring.data.redis.host:localhost}}}}")
    private String coreHost;

    @Value("${mafiagame.redis.core.port:${REDIS_CORE_PORT:${CORE_TIMER_REDIS_PORT:${spring.data.redis.port:6379}}}}")
    private int corePort;

    @Value("${mafiagame.redis.core.database:${REDIS_CORE_DATABASE:${CORE_TIMER_REDIS_DATABASE:0}}}")
    private int coreDatabase;

    @Value("${mafiagame.redis.core.password:${REDIS_CORE_PASSWORD:${CORE_TIMER_REDIS_PASSWORD:}}}")
    private String corePassword;

    @Value("${mafiagame.redis.core.sentinel.master:${REDIS_CORE_SENTINEL_MASTER:${CORE_TIMER_REDIS_SENTINEL_MASTER:}}}")
    private String coreSentinelMaster;

    @Value("${mafiagame.redis.core.sentinel.nodes:${REDIS_CORE_SENTINEL_NODES:${CORE_TIMER_REDIS_SENTINEL_NODES:}}}")
    private String coreSentinelNodes;

    @Value("${mafiagame.redis.support.host:${REDIS_SUPPORT_HOST:${SUPPORT_REDIS_HOST:${REDIS_HOST:${spring.data.redis.host:localhost}}}}}")
    private String supportHost;

    @Value("${mafiagame.redis.support.port:${REDIS_SUPPORT_PORT:${SUPPORT_REDIS_PORT:${REDIS_PORT:${spring.data.redis.port:6379}}}}}")
    private int supportPort;

    @Value("${mafiagame.redis.support.database:${REDIS_SUPPORT_DATABASE:${SUPPORT_REDIS_DATABASE:0}}}")
    private int supportDatabase;

    @Value("${mafiagame.redis.support.password:${REDIS_SUPPORT_PASSWORD:${SUPPORT_REDIS_PASSWORD:}}}")
    private String supportPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient coreRedissonClient() {
        Config config = new Config();
        if (hasText(coreSentinelMaster) && hasText(coreSentinelNodes)) {
            var sentinelConfig = config.useSentinelServers()
                    .setMasterName(coreSentinelMaster)
                    .setDatabase(coreDatabase)
                    .setMasterConnectionMinimumIdleSize(1)
                    .setMasterConnectionPoolSize(4)
                    .setSlaveConnectionMinimumIdleSize(1)
                    .setSlaveConnectionPoolSize(4);
            sentinelNodes(coreSentinelNodes).forEach(node -> sentinelConfig.addSentinelAddress(redisAddress(node)));
            if (hasText(corePassword)) {
                sentinelConfig.setPassword(corePassword);
            }
        } else {
            var singleServerConfig = config.useSingleServer()
                    .setAddress(redisAddress(coreHost + ":" + corePort))
                    .setDatabase(coreDatabase)
                    .setConnectionMinimumIdleSize(1)
                    .setConnectionPoolSize(4);
            if (hasText(corePassword)) {
                singleServerConfig.setPassword(corePassword);
            }
        }
        return Redisson.create(config);
    }

    @Bean(destroyMethod = "shutdown")
    @Primary
    public RedissonClient redissonClient() {
        Config config = new Config();
        var singleServerConfig = config.useSingleServer()
                .setAddress(redisAddress(supportHost + ":" + supportPort))
                .setDatabase(supportDatabase)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2);
        if (hasText(supportPassword)) {
            singleServerConfig.setPassword(supportPassword);
        }

        return Redisson.create(config);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private List<String> sentinelNodes(String nodes) {
        return Arrays.stream(nodes.split(","))
                .map(String::trim)
                .filter(this::hasText)
                .toList();
    }

    private String redisAddress(String hostAndPort) {
        return "redis://" + hostAndPort;
    }
}
