package com.example.mafiagame.global.config;

import com.example.mafiagame.chat.domain.ChatRoom;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        
        // ChatRoom 객체를 JSON 형태로 저장하기 위한 Serializer 설정
        Jackson2JsonRedisSerializer<ChatRoom> chatRoomSerializer = new Jackson2JsonRedisSerializer<>(ChatRoom.class);
        redisTemplate.setValueSerializer(chatRoomSerializer);
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(chatRoomSerializer);
        
        return redisTemplate;
    }
}