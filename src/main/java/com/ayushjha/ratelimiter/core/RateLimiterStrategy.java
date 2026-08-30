package com.ayushjha.ratelimiter.core;

/**
 * Strategy pattern seam: every algorithm is interchangeable behind this contract,
 * so the service layer and the HTTP filter never branch on algorithm type.
 *
 * <p>Implementations must be safe for concurrent use and must make their decision
 * in a single atomic Redis operation - read-modify-write split across round trips
 * would let two instances both admit the request that exhausts the quota.
 */
public interface RateLimiterStrategy {

    /** Which enum constant this implementation serves. */
    Algorithm algorithm();

    /**
     * Evaluates and consumes quota atomically.
     *
     * @throws org.springframework.dao.DataAccessException if Redis is unreachable;
     *         the caller decides whether to fail open or closed
     */
    RateLimitDecision tryConsume(RateLimitContext context);

    /**
     * Reports current state without consuming quota. Useful for dashboards and
     * pre-flight checks. Implemented as a zero-cost evaluation.
     */
    default RateLimitDecision peek(RateLimitContext context) {
        return tryConsume(new RateLimitContext(context.subject(), context.policy(), 0L, null));
    }

    /** Clears all state for a subject, restoring full quota immediately. */
    void reset(String subject, RateLimitPolicy policy);
}
