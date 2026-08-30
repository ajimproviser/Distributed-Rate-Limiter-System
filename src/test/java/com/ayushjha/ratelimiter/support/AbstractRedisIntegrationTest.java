package com.ayushjha.ratelimiter.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

/**
 * Runs the suite against a real Redis. The algorithms live in Lua and depend on
 * the server's clock and on genuine atomicity, so a mock or an in-memory fake
 * would verify nothing that matters here.
 *
 * <p>Uses the singleton container pattern rather than {@code @Container}: one Redis
 * is started for the whole JVM and reused by every test class, instead of being
 * torn down and re-created per class.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractRedisIntegrationTest {

    private static final int REDIS_PORT = 6379;

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .withCommand("redis-server", "--save", "", "--appendonly", "no");

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(REDIS_PORT));
    }

    /**
     * Fresh subject per test. Isolating by key is cheaper and less flaky than
     * flushing the database between methods, and it keeps tests parallel-safe.
     */
    protected static String uniqueSubject(String label) {
        return label + "-" + UUID.randomUUID();
    }
}
