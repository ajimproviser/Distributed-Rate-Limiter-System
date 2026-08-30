package com.ayushjha.ratelimiter.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to evaluate and consume quota for a subject")
public record RateLimitCheckRequest(

        @Schema(description = "Identity being limited: tenant, API key, user id or IP",
                example = "merchant-4417", requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank(message = "subject is required")
        @Size(max = 128, message = "subject must be at most 128 characters")
        String subject,

        @Schema(description = "Policy to apply. Omit to use the configured default.",
                example = "payments-standard")
        String policy,

        @Schema(description = "Units to consume. Weight expensive operations above 1. "
                + "Use 0 to inspect quota without consuming.", example = "1", defaultValue = "1")
        @Min(value = 0, message = "cost cannot be negative")
        Long cost,

        @Schema(description = "Idempotency key. Repeating a request id replays the original "
                + "verdict instead of consuming quota again.",
                example = "b3d1f0a2-6c19-4f2a-9c1e-2f77c0a51e44")
        @Size(max = 128, message = "requestId must be at most 128 characters")
        String requestId
) {

    private static final long DEFAULT_COST = 1L;

    public long costOrDefault() {
        return cost == null ? DEFAULT_COST : cost;
    }
}
