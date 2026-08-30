package com.ayushjha.ratelimiter.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;

/**
 * A validated, fully resolved limit definition. Configuration is expressed as
 * "{@code limit} requests per {@code window}"; the derived token-bucket figures
 * are computed once here so the request path never repeats the arithmetic.
 */
public record RateLimitPolicy(
        String name,
        Algorithm algorithm,
        long limit,
        Duration window,
        long capacity,
        double refillTokensPerMillis,
        Duration idempotencyTtl,
        String description
) {

    public RateLimitPolicy {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Policy name is required");
        }
        if (algorithm == null) {
            throw new IllegalArgumentException("Policy '" + name + "' is missing an algorithm");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("Policy '" + name + "' must allow at least 1 request, got " + limit);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("Policy '" + name + "' needs a positive window, got " + window);
        }
        if (capacity < limit) {
            throw new IllegalArgumentException(
                    "Policy '" + name + "' burst (" + capacity + ") cannot be below its limit (" + limit + ")");
        }
    }

    /**
     * Refill rate as a plain decimal string. Lua parses this with {@code tonumber},
     * and plain notation avoids handing it an exponent form for very slow refills.
     */
    public String refillRateAsPlainString() {
        return BigDecimal.valueOf(refillTokensPerMillis)
                .round(new MathContext(12))
                .stripTrailingZeros()
                .toPlainString();
    }

    /** Sustained throughput this policy admits, for documentation and metrics. */
    public double requestsPerSecond() {
        return (double) limit * 1000d / window.toMillis();
    }
}
