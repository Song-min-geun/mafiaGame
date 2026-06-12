package com.example.mafiagame.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

import static org.mockito.Mockito.mock;

/**
 * 테스트 환경에서 Kafka 브로커 없이 컨텍스트가 로딩되도록
 * KafkaTemplate을 mock으로 제공한다.
 *
 * 실제 Kafka Config({@code KafkaProducerConfig}, {@code KafkaConsumerConfig})는
 * {@code @Profile("!test")}로 테스트에서 제외된다.
 */
@TestConfiguration
public class TestKafkaConfig {

    @Bean
    @SuppressWarnings("unchecked")
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return mock(KafkaTemplate.class);
    }
}
