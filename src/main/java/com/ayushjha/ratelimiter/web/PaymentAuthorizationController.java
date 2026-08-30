package com.ayushjha.ratelimiter.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A stand-in for the protected downstream: it does no real work, it exists so the
 * enforcement path can be exercised end to end. Everything under
 * {@code /api/v1/payments/**} is guarded by the filter, so excess traffic is
 * rejected with 429 before reaching this class.
 */
@RestController
@RequestMapping(value = "/api/v1/payments", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Payments (guarded demo)",
        description = "Protected endpoint demonstrating live enforcement with 429 responses")
public class PaymentAuthorizationController {

    @PostMapping("/authorize")
    @Operation(
            summary = "Authorize a payment (rate limited)",
            description = """
                    Guarded by the `payments-standard` policy. The caller identity comes from \
                    `X-API-Key`, falling back to `X-Tenant-Id` and then the client IP, so each \
                    caller gets its own quota.

                    Send `Idempotency-Key` to make retries safe: a repeat of the same key does \
                    not consume quota a second time.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authorized"),
            @ApiResponse(responseCode = "429", description = "Quota exhausted; see Retry-After",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    })
    public AuthorizationResponse authorize(@Valid @RequestBody AuthorizationRequest request) {
        return new AuthorizationResponse(
                "auth_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                "APPROVED",
                request.amount(),
                request.currency(),
                Instant.now());
    }

    @PostMapping("/otp")
    @Operation(
            summary = "Send a payment confirmation OTP (tightly rate limited)",
            description = """
                    Guarded by the `otp-requests` policy, which uses the **sliding window** \
                    algorithm: 3 sends per rolling minute, exactly. A fixed-window counter \
                    would let a caller send 3 just before a boundary and 3 just after, \
                    doubling the real fan-out - which is a cost and a spam problem, not just \
                    a quota one.

                    Because this rule is listed before `/api/v1/payments/**` in configuration, \
                    it wins: enforcement rules are matched in order, most specific first.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OTP dispatched"),
            @ApiResponse(responseCode = "429", description = "Too many OTP requests; see Retry-After",
                    content = @io.swagger.v3.oas.annotations.media.Content(
                            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE))
    })
    public OtpResponse sendOtp(@Valid @RequestBody OtpRequest request) {
        return new OtpResponse(
                "otp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                "SENT",
                maskPhone(request.phone()),
                Instant.now());
    }

    /** Never echo a full phone number back to the caller or into a log. */
    private static String maskPhone(String phone) {
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? "****" : "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    @Schema(description = "Payment authorization request")
    public record AuthorizationRequest(

            @Schema(example = "ord_88213", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String orderId,

            @Schema(example = "149.99", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount,

            @Schema(example = "INR", description = "ISO 4217 currency code")
            @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
            String currency
    ) {
        public AuthorizationRequest {
            if (currency == null || currency.isBlank()) {
                currency = "INR";
            }
        }
    }

    @Schema(description = "Payment authorization result")
    public record AuthorizationResponse(
            @Schema(example = "auth_9f2c1ab74e0d5836") String authorizationId,
            @Schema(example = "APPROVED") String status,
            BigDecimal amount,
            String currency,
            Instant authorizedAt
    ) {
    }

    @Schema(description = "Request to send a payment confirmation OTP")
    public record OtpRequest(

            @Schema(example = "ord_88213", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String orderId,

            @Schema(example = "+919876543210", requiredMode = Schema.RequiredMode.REQUIRED)
            @NotBlank String phone
    ) {
    }

    @Schema(description = "OTP dispatch result")
    public record OtpResponse(
            @Schema(example = "otp_4f2c1ab74e0d") String otpId,
            @Schema(example = "SENT") String status,
            @Schema(description = "Masked destination", example = "********3210") String sentTo,
            Instant sentAt
    ) {
    }
}
