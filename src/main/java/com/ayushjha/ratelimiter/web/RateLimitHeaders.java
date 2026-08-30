package com.ayushjha.ratelimiter.web;

import com.ayushjha.ratelimiter.core.RateLimitDecision;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;

/**
 * Writes the quota headers clients use to self-throttle. Emitting these on allowed
 * responses - not just on rejections - is what lets a well-behaved caller slow down
 * before it ever sees a 429.
 */
public final class RateLimitHeaders {

    public static final String LIMIT = "X-RateLimit-Limit";
    public static final String REMAINING = "X-RateLimit-Remaining";
    public static final String RESET = "X-RateLimit-Reset";
    public static final String POLICY = "X-RateLimit-Policy";
    public static final String ALGORITHM = "X-RateLimit-Algorithm";
    public static final String REPLAYED = "X-RateLimit-Replayed";
    public static final String DEGRADED = "X-RateLimit-Degraded";

    private RateLimitHeaders() {
    }

    public static void apply(HttpServletResponse response, RateLimitDecision decision) {
        response.setHeader(LIMIT, Long.toString(decision.limit()));
        response.setHeader(REMAINING, Long.toString(Math.max(0, decision.remaining())));
        response.setHeader(RESET, Long.toString(decision.resetAfterSeconds()));
        response.setHeader(POLICY, decision.policy());
        response.setHeader(ALGORITHM, decision.algorithm().name());

        if (decision.replayed()) {
            response.setHeader(REPLAYED, "true");
        }
        if (decision.degraded()) {
            response.setHeader(DEGRADED, "true");
        }
        if (!decision.allowed()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(decision.retryAfterSeconds()));
        }
    }
}
