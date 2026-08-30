package com.ayushjha.ratelimiter.web;

import com.ayushjha.ratelimiter.core.RateLimitDecision;
import com.ayushjha.ratelimiter.service.RateLimiterService;
import com.ayushjha.ratelimiter.web.dto.RateLimitCheckRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rate limiting offered as a service to other systems, so a fleet of applications
 * can share one quota without each embedding the algorithm.
 */
@RestController
@RequestMapping(value = "/api/v1/rate-limit", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Rate Limiter", description = "Evaluate, inspect and reset quotas")
public class RateLimiterController {

    private final RateLimiterService rateLimiter;

    public RateLimiterController(RateLimiterService rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/check")
    @Operation(
            summary = "Evaluate and consume quota",
            description = """
                    Always answers 200 with the verdict in the body: this endpoint is an oracle \
                    for other services, which are expected to act on `allowed` themselves. \
                    For enforcement that returns 429 directly, see the guarded demo endpoint \
                    under /api/v1/payments.

                    Quota headers are set on every response, and supplying `requestId` makes the \
                    call replay-safe.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Verdict reached"),
            @ApiResponse(responseCode = "400", description = "Invalid request or unknown policy", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "503", description = "Redis unreachable and fail-open disabled", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public RateLimitDecision check(@Valid @RequestBody RateLimitCheckRequest request,
                                   HttpServletResponse response) {
        RateLimitDecision decision = rateLimiter.check(
                request.subject(), request.policy(), request.costOrDefault(), request.requestId());
        RateLimitHeaders.apply(response, decision);
        return decision;
    }

    @GetMapping("/status/{subject}")
    @Operation(
            summary = "Inspect remaining quota without consuming",
            description = "Read-only probe for dashboards and pre-flight checks.")
    public RateLimitDecision status(
            @Parameter(description = "Identity to inspect", example = "merchant-4417")
            @PathVariable String subject,

            @Parameter(description = "Policy to inspect against. Omit for the default.",
                    example = "payments-standard")
            @RequestParam(required = false) String policy,

            HttpServletResponse response) {

        RateLimitDecision decision = rateLimiter.peek(subject, policy);
        RateLimitHeaders.apply(response, decision);
        return decision;
    }

    @DeleteMapping("/{subject}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Reset a subject to full quota",
            description = """
                    Support tooling for clearing a limit early, e.g. after a merchant is \
                    manually unblocked. Idempotency markers are deliberately left to expire \
                    so an in-flight retry cannot consume quota twice.
                    """)
    public void reset(@PathVariable String subject,
                      @RequestParam(required = false) String policy) {
        rateLimiter.reset(subject, policy);
    }
}
