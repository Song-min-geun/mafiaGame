package com.example.mafiagame.global.config;

import com.example.mafiagame.chat.domain.ChatRoom;
import com.example.mafiagame.game.domain.entity.Game;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
@EnableCaching
public class RedisConfig {

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

    @Bean
    public RedisConnectionFactory coreRedisConnectionFactory() {
        if (hasText(coreSentinelMaster) && hasText(coreSentinelNodes)) {
            RedisSentinelConfiguration sentinelConfiguration = new RedisSentinelConfiguration()
                    .master(coreSentinelMaster);
            sentinelNodes(coreSentinelNodes).forEach(node -> {
                String[] hostAndPort = node.split(":", 2);
                sentinelConfiguration.sentinel(hostAndPort[0], Integer.parseInt(hostAndPort[1]));
            });
            sentinelConfiguration.setDatabase(coreDatabase);
            if (hasText(corePassword)) {
                sentinelConfiguration.setPassword(RedisPassword.of(corePassword));
            }
            return new LettuceConnectionFactory(sentinelConfiguration);
        }

        RedisStandaloneConfiguration standaloneConfiguration = new RedisStandaloneConfiguration(coreHost, corePort);
        standaloneConfiguration.setDatabase(coreDatabase);
        if (hasText(corePassword)) {
            standaloneConfiguration.setPassword(RedisPassword.of(corePassword));
        }
        return new LettuceConnectionFactory(standaloneConfiguration);
    }

    @Bean
    @Primary
    public RedisConnectionFactory supportRedisConnectionFactory() {
        RedisStandaloneConfiguration standaloneConfiguration = new RedisStandaloneConfiguration(supportHost, supportPort);
        standaloneConfiguration.setDatabase(supportDatabase);
        if (hasText(supportPassword)) {
            standaloneConfiguration.setPassword(RedisPassword.of(supportPassword));
        }
        return new LettuceConnectionFactory(standaloneConfiguration);
    }

    @Bean
    public StringRedisTemplate coreStringRedisTemplate(
            @Qualifier("coreRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    @Primary
    public StringRedisTemplate stringRedisTemplate(
            @Qualifier("supportRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    @Bean
    public RedisTemplate<String, Object> coreRedisTemplate(
            @Qualifier("coreRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return objectRedisTemplate(connectionFactory);
    }

    @Bean
    @Primary
    public RedisTemplate<String, Object> redisTemplate(
            @Qualifier("supportRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        return objectRedisTemplate(connectionFactory);
    }

    private RedisTemplate<String, Object> objectRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        Jackson2JsonRedisSerializer<Object> jsonSerializer = new Jackson2JsonRedisSerializer<>(objectMapper,
                Object.class);
        redisTemplate.setValueSerializer(jsonSerializer);
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(jsonSerializer);

        return redisTemplate;
    }

    @Bean
    public RedisTemplate<String, ChatRoom> chatRoomRedisTemplate(
            @Qualifier("supportRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, ChatRoom> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new Jackson2JsonRedisSerializer<>(ChatRoom.class));
        return redisTemplate;
    }

    @Bean
    public RedisTemplate<String, Game> gameRedisTemplate(
            @Qualifier("supportRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Game> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new Jackson2JsonRedisSerializer<>(Game.class));
        return redisTemplate;
    }

    @Bean
    public CacheManager cacheManager(
            @Qualifier("supportRedisConnectionFactory") RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration cacheConfiguration = RedisCacheConfiguration
                .defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10)) // 캐시 유효 시간 10분
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new GenericJackson2JsonRedisSerializer()));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(cacheConfiguration)
                .build();
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
}
