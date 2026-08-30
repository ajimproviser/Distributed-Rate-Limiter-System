package com.ayushjha.ratelimiter.core;

/**
 * Selector for the rate limiting {@link RateLimiterStrategy} implementations.
 *
 * <p>Adding an algorithm means adding a constant here and a matching
 * {@code @Component} strategy; the registry wires it up with no other edits.
 */
public enum Algorithm {

    /**
     * Continuously refilling bucket. Absorbs bursts up to a configured capacity,
     * then admits at the sustained rate. O(1) memory per subject, so this is the
     * choice for high-volume quotas.
     */
    TOKEN_BUCKET,

    /**
     * Exact rolling-window log. No boundary burst, at the cost of one sorted-set
     * member per in-window request. Best for small, security-sensitive limits.
     */
    SLIDING_WINDOW
}
