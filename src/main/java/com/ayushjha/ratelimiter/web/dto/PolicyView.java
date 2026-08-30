package com.ayushjha.ratelimiter.web.dto;

import com.ayushjha.ratelimiter.core.Algorithm;
import com.ayushjha.ratelimiter.core.RateLimitPolicy;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "A configured rate limit policy")
public record PolicyView(

        @Schema(example = "payments-standard")
        String name,

        Algorithm algorithm,

        @Schema(description = "Requests admitted per window", example = "1700")
        long limit,

        @Schema(description = "Rolling window length", example = "PT1S")
        String window,

        @Schema(description = "Largest instantaneous burst (token bucket capacity)", example = "2000")
        long burst,

        @Schema(description = "Sustained throughput this policy admits", example = "1700.0")
        double sustainedRequestsPerSecond,

        @Schema(description = "Equivalent sustained throughput per minute", example = "102000.0")
        double sustainedRequestsPerMinute,

        @Schema(description = "How long a request id is remembered for replay", example = "PT5M")
        String idempotencyTtl,

        String description
) {

    public static PolicyView from(RateLimitPolicy policy) {
        return new PolicyView(
                policy.name(),
                policy.algorithm(),
                policy.limit(),
                policy.window().toString(),
                policy.capacity(),
                policy.requestsPerSecond(),
                policy.requestsPerSecond() * 60d,
                policy.idempotencyTtl().toString(),
                policy.description());
    }
}
