package com.ayushjha.ratelimiter.core;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The verdict for a single evaluation, carrying everything needed to build the
 * standard {@code X-RateLimit-*} and {@code Retry-After} response headers.
 */
@Schema(description = "Outcome of a rate limit evaluation")
public record RateLimitDecision(

        @Schema(description = "Whether the request may proceed", example = "true")
        boolean allowed,

        @Schema(description = "Subject the limit was applied to", example = "merchant-4417")
        String subject,

        @Schema(description = "Policy that produced this decision", example = "payments-standard")
        String policy,

        @Schema(description = "Algorithm backing the policy")
        Algorithm algorithm,

        @Schema(description = "Maximum units available in a full window", example = "2000")
        long limit,

        @Schema(description = "Units still available to this subject", example = "1998")
        long remaining,

        @Schema(description = "Milliseconds until a retry could succeed; 0 when allowed", example = "0")
        long retryAfterMillis,

        @Schema(description = "Milliseconds until the subject is back to full quota", example = "1000")
        long resetAfterMillis,

        @Schema(description = "True when a repeated request id replayed an earlier verdict", example = "false")
        boolean replayed,

        @Schema(description = "True when Redis was unreachable and the fail-open policy applied", example = "false")
        boolean degraded
) {

    /**
     * Verdict used when Redis is unreachable and {@code ratelimiter.fail-open} is
     * enabled: admit the request but mark it degraded so dashboards and callers
     * can see that enforcement was not actually applied.
     */
    public static RateLimitDecision failOpen(RateLimitContext context) {
        RateLimitPolicy policy = context.policy();
        return new RateLimitDecision(
                true,
                context.subject(),
                policy.name(),
                policy.algorithm(),
                policy.capacity(),
                policy.capacity(),
                0L,
                0L,
                false,
                true);
    }

    /** Seconds value for the {@code Retry-After} header, which has second granularity. */
    public long retryAfterSeconds() {
        return retryAfterMillis <= 0 ? 0 : Math.max(1, Math.round(retryAfterMillis / 1000d));
    }

    public long resetAfterSeconds() {
        return resetAfterMillis <= 0 ? 0 : Math.max(1, Math.round(resetAfterMillis / 1000d));
    }
}
