package com.ayushjha.ratelimiter.policy;

import com.ayushjha.ratelimiter.config.RateLimiterProperties;
import com.ayushjha.ratelimiter.core.Algorithm;
import com.ayushjha.ratelimiter.core.RateLimitPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure configuration logic, so no Redis is involved. */
class PolicyCatalogTest {

    @Test
    @DisplayName("derives the refill rate from limit and window")
    void derivesRefillRate() {
        PolicyCatalog catalog = catalogOf("standard", policy(Algorithm.TOKEN_BUCKET, 100, Duration.ofSeconds(1), null));

        RateLimitPolicy resolved = catalog.resolve("standard");

        assertThat(resolved.refillTokensPerMillis()).isEqualTo(0.1d);
        assertThat(resolved.requestsPerSecond()).isEqualTo(100d);
    }

    @Test
    @DisplayName("defaults burst capacity to the sustained limit when no burst is configured")
    void defaultsBurstToLimit() {
        PolicyCatalog catalog = catalogOf("standard", policy(Algorithm.TOKEN_BUCKET, 250, Duration.ofSeconds(1), null));

        assertThat(catalog.resolve("standard").capacity()).isEqualTo(250L);
    }

    @Test
    @DisplayName("keeps configured burst headroom above the sustained limit")
    void honoursConfiguredBurst() {
        PolicyCatalog catalog = catalogOf("standard", policy(Algorithm.TOKEN_BUCKET, 100, Duration.ofSeconds(1), 400L));

        assertThat(catalog.resolve("standard").capacity()).isEqualTo(400L);
    }

    @Test
    @DisplayName("formats very slow refill rates as plain decimals for Lua")
    void formatsSlowRefillWithoutExponent() {
        PolicyCatalog catalog = catalogOf("otp", policy(Algorithm.SLIDING_WINDOW, 3, Duration.ofHours(1), null));

        String refill = catalog.resolve("otp").refillRateAsPlainString();

        assertThat(refill)
                .as("Lua's tonumber must not be handed scientific notation")
                .doesNotContainIgnoringCase("e");
        assertThat(Double.parseDouble(refill)).isPositive();
    }

    @Test
    @DisplayName("falls back to the default policy when none is named")
    void fallsBackToDefaultPolicy() {
        PolicyCatalog catalog = catalogOf("standard", policy(Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), null));

        assertThat(catalog.resolve(null).name()).isEqualTo("standard");
        assertThat(catalog.resolve("  ").name()).isEqualTo("standard");
        assertThat(catalog.defaultPolicy().name()).isEqualTo("standard");
    }

    @Test
    @DisplayName("rejects an unknown policy and lists the valid names")
    void rejectsUnknownPolicy() {
        PolicyCatalog catalog = catalogOf("standard", policy(Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), null));

        assertThatThrownBy(() -> catalog.resolve("nope"))
                .isInstanceOf(UnknownPolicyException.class)
                .hasMessageContaining("nope")
                .hasMessageContaining("standard");
    }

    @Test
    @DisplayName("fails fast when the default policy is not defined")
    void failsWhenDefaultPolicyMissing() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setDefaultPolicy("missing");
        properties.setPolicies(Map.of("standard", policy(Algorithm.TOKEN_BUCKET, 10, Duration.ofSeconds(1), null)));

        assertThatThrownBy(() -> new PolicyCatalog(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("fails fast when no policies are configured at all")
    void failsWhenNoPoliciesConfigured() {
        assertThatThrownBy(() -> new PolicyCatalog(new RateLimiterProperties()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No policies configured");
    }

    @Test
    @DisplayName("rejects a burst below the sustained limit, which would be contradictory")
    void rejectsBurstBelowLimit() {
        assertThatThrownBy(() ->
                catalogOf("broken", policy(Algorithm.TOKEN_BUCKET, 100, Duration.ofSeconds(1), 10L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("broken");
    }

    private static PolicyCatalog catalogOf(String name, RateLimiterProperties.Policy policy) {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setDefaultPolicy(name);
        Map<String, RateLimiterProperties.Policy> policies = new LinkedHashMap<>();
        policies.put(name, policy);
        properties.setPolicies(policies);
        return new PolicyCatalog(properties);
    }

    private static RateLimiterProperties.Policy policy(Algorithm algorithm, long limit, Duration window, Long burst) {
        RateLimiterProperties.Policy policy = new RateLimiterProperties.Policy();
        policy.setAlgorithm(algorithm);
        policy.setLimit(limit);
        policy.setWindow(window);
        policy.setBurst(burst);
        return policy;
    }
}
