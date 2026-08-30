package com.ayushjha.ratelimiter.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Redis wiring. The connection factory, pool and {@code StringRedisTemplate} come
 * from Spring Boot's auto-configuration driven by {@code spring.data.redis.*}.
 *
 * <p>Scripts are loaded once at startup and cached as singletons keyed by their
 * SHA-1. Spring Data sends {@code EVALSHA} and only falls back to a full
 * {@code EVAL} if the server reports the script is unknown, so steady-state
 * traffic ships a 40-byte digest instead of the whole program on every request.
 */
@Configuration(proxyBeanMethods = false)
public class RedisConfig {

    @Bean
    public RedisScript<List<Long>> tokenBucketScript() {
        return numericArrayScript("scripts/token_bucket.lua");
    }

    @Bean
    public RedisScript<List<Long>> slidingWindowScript() {
        return numericArrayScript("scripts/sliding_window.lua");
    }

    @SuppressWarnings("unchecked")
    private static RedisScript<List<Long>> numericArrayScript(String classpathLocation) {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(classpathLocation));
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }
}
