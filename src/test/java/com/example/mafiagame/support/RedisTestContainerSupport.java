package com.example.mafiagame.support;

import java.nio.file.Path;

import org.junit.jupiter.api.TestInstance;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class RedisTestContainerSupport {

    private static final int REDIS_PORT = 6379;
    private static final Path REDIS_IMAGE_DIR = Path.of("src/test/resources/testcontainers/redis");

    @Container
    protected static final GenericContainer<?> CORE_REDIS = redisContainer(
            "mafiagame-test-core-redis:local",
            "core");

    @Container
    protected static final GenericContainer<?> SUPPORT_REDIS = redisContainer(
            "mafiagame-test-support-redis:local",
            "support");

    @DynamicPropertySource
    static void overrideRedisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", CORE_REDIS::getHost);
        registry.add("spring.data.redis.port", () -> CORE_REDIS.getMappedPort(REDIS_PORT));

        registry.add("mafiagame.redis.core.host", CORE_REDIS::getHost);
        registry.add("mafiagame.redis.core.port", () -> CORE_REDIS.getMappedPort(REDIS_PORT));
        registry.add("mafiagame.redis.support.host", SUPPORT_REDIS::getHost);
        registry.add("mafiagame.redis.support.port", () -> SUPPORT_REDIS.getMappedPort(REDIS_PORT));
    }

    private static GenericContainer<?> redisContainer(String imageName, String profile) {
        Path profileDir = REDIS_IMAGE_DIR.resolve(profile);
        ImageFromDockerfile image = new ImageFromDockerfile(imageName, false)
                .withDockerfile(profileDir.resolve("Dockerfile"))
                .withFileFromPath("redis.conf", profileDir.resolve("redis.conf"));

        return new GenericContainer<>(image)
                .withExposedPorts(REDIS_PORT)
                .withStartupAttempts(3)
                .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));
    }
}
