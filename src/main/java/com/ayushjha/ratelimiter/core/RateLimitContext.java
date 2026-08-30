package com.ayushjha.ratelimiter.core;

/**
 * One rate limit evaluation.
 *
 * @param subject  who is being limited - tenant, API key, user id or client IP
 * @param policy   the resolved limit to apply
 * @param cost     units to consume; a heavier operation can weigh more than 1,
 *                 and {@code 0} probes current state without consuming
 * @param requestId caller-supplied idempotency key, or {@code null}. When present,
 *                 a retry of the same request replays the original verdict instead
 *                 of consuming quota again.
 */
public record RateLimitContext(String subject, RateLimitPolicy policy, long cost, String requestId) {

    public RateLimitContext {
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Rate limit subject is required");
        }
        if (policy == null) {
            throw new IllegalArgumentException("Rate limit policy is required");
        }
        if (cost < 0) {
            throw new IllegalArgumentException("Cost cannot be negative, got " + cost);
        }
        if (cost > policy.capacity()) {
            throw new IllegalArgumentException(
                    "Cost " + cost + " can never be satisfied by policy '" + policy.name()
                            + "' with capacity " + policy.capacity());
        }
    }

    public boolean isProbe() {
        return cost == 0;
    }

    public boolean hasRequestId() {
        return requestId != null && !requestId.isBlank();
    }
}
