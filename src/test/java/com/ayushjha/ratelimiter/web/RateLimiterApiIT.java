package com.ayushjha.ratelimiter.web;

import com.ayushjha.ratelimiter.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class RateLimiterApiIT extends AbstractRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("POST /check returns a verdict and the quota headers")
    void checkReturnsVerdictWithHeaders() throws Exception {
        String subject = uniqueSubject("api");

        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "%s", "policy": "bucket-slow"}
                                """.formatted(subject)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.subject").value(subject))
                .andExpect(jsonPath("$.policy").value("bucket-slow"))
                .andExpect(jsonPath("$.algorithm").value("TOKEN_BUCKET"))
                .andExpect(jsonPath("$.remaining").value(1))
                .andExpect(header().string(RateLimitHeaders.LIMIT, "2"))
                .andExpect(header().string(RateLimitHeaders.REMAINING, "1"))
                .andExpect(header().string(RateLimitHeaders.POLICY, "bucket-slow"));
    }

    @Test
    @DisplayName("POST /check reports a blocked verdict once quota is spent")
    void checkReportsBlockedVerdict() throws Exception {
        String subject = uniqueSubject("api-blocked");
        String body = """
                {"subject": "%s", "policy": "bucket-slow"}
                """.formatted(subject);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/rate-limit/check")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.allowed").value(true));
        }

        // Still HTTP 200: this endpoint reports a verdict, it does not enforce one.
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.retryAfterMillis").value(greaterThan(0)))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("an unknown policy is a 400 that names the valid options")
    void unknownPolicyIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "someone", "policy": "does-not-exist"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unknown Policy"))
                .andExpect(jsonPath("$.requestedPolicy").value("does-not-exist"))
                .andExpect(jsonPath("$.knownPolicies").isArray());
    }

    @Test
    @DisplayName("a missing subject fails validation with field-level detail")
    void missingSubjectIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "  "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.subject").exists());
    }

    @Test
    @DisplayName("GET /status inspects quota without consuming it")
    void statusDoesNotConsume() throws Exception {
        String subject = uniqueSubject("api-status");

        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subject": "%s", "policy": "bucket-slow"}
                                """.formatted(subject)))
                .andExpect(status().isOk());

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/rate-limit/status/{subject}", subject)
                            .param("policy", "bucket-slow"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.remaining").value(1));
        }
    }

    @Test
    @DisplayName("DELETE restores a spent subject to full quota")
    void resetRestoresQuota() throws Exception {
        String subject = uniqueSubject("api-reset");
        String body = """
                {"subject": "%s", "policy": "bucket-slow"}
                """.formatted(subject);

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/rate-limit/check")
                    .contentType(MediaType.APPLICATION_JSON).content(body));
        }
        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.allowed").value(false));

        mockMvc.perform(delete("/api/v1/rate-limit/{subject}", subject)
                        .param("policy", "bucket-slow"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/rate-limit/check")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    @DisplayName("the guarded payments endpoint answers 429 with a problem document once throttled")
    void guardedEndpointEnforcesWith429() throws Exception {
        String apiKey = uniqueSubject("merchant");

        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(authorizePayment(apiKey, null))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("APPROVED"))
                    .andExpect(header().string(RateLimitHeaders.POLICY, "bucket-slow"));
        }

        mockMvc.perform(authorizePayment(apiKey, null))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string(RateLimitHeaders.REMAINING, "0"))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.policy").value("bucket-slow"))
                .andExpect(jsonPath("$.retryAfterMillis").value(greaterThan(0)));
    }

    @Test
    @DisplayName("callers are throttled independently of one another")
    void quotasAreIsolatedPerApiKey() throws Exception {
        String noisy = uniqueSubject("noisy-merchant");
        String quiet = uniqueSubject("quiet-merchant");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(authorizePayment(noisy, null)).andExpect(status().isOk());
        }
        mockMvc.perform(authorizePayment(noisy, null)).andExpect(status().isTooManyRequests());
        mockMvc.perform(authorizePayment(quiet, null)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an Idempotency-Key makes a retried payment free of charge")
    void idempotencyKeyPreventsDoubleCharging() throws Exception {
        String apiKey = uniqueSubject("retrying-merchant");
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(authorizePayment(apiKey, idempotencyKey))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitHeaders.REMAINING, "1"));

        // Three network retries of the same logical request must cost one token total.
        for (int retry = 0; retry < 3; retry++) {
            mockMvc.perform(authorizePayment(apiKey, idempotencyKey))
                    .andExpect(status().isOk())
                    .andExpect(header().string(RateLimitHeaders.REPLAYED, "true"))
                    .andExpect(header().string(RateLimitHeaders.REMAINING, "1"));
        }

        mockMvc.perform(authorizePayment(apiKey, null)).andExpect(status().isOk());
        mockMvc.perform(authorizePayment(apiKey, null)).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("actuator stays reachable while a caller is throttled")
    void infrastructureEndpointsAreNeverLimited() throws Exception {
        String apiKey = uniqueSubject("throttled-merchant");

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(authorizePayment(apiKey, null));
        }
        mockMvc.perform(authorizePayment(apiKey, null)).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/actuator/health").header("X-API-Key", apiKey))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a more specific enforcement rule wins over the catch-all")
    void mostSpecificEnforcementRuleWins() throws Exception {
        String apiKey = uniqueSubject("otp-caller");

        // /payments/otp is bound to the sliding-window policy, while everything else
        // under /payments falls through to the token bucket.
        mockMvc.perform(sendOtp(apiKey))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitHeaders.POLICY, "window-small"))
                .andExpect(header().string(RateLimitHeaders.ALGORITHM, "SLIDING_WINDOW"));

        mockMvc.perform(authorizePayment(apiKey, null))
                .andExpect(status().isOk())
                .andExpect(header().string(RateLimitHeaders.POLICY, "bucket-slow"))
                .andExpect(header().string(RateLimitHeaders.ALGORITHM, "TOKEN_BUCKET"));
    }

    @Test
    @DisplayName("the sliding-window guarded endpoint enforces its rolling limit over HTTP")
    void slidingWindowGuardedEndpointEnforcesLimit() throws Exception {
        String apiKey = uniqueSubject("otp-spammer");

        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(sendOtp(apiKey))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("SENT"))
                    .andExpect(jsonPath("$.sentTo").value("********3210"));
        }

        mockMvc.perform(sendOtp(apiKey))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.algorithm").value("SLIDING_WINDOW"))
                .andExpect(header().exists("Retry-After"));
    }

    @Test
    @DisplayName("GET /policies exposes the configured limits")
    void listsPolicies() throws Exception {
        mockMvc.perform(get("/api/v1/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == 'bucket-slow')].limit").value(2))
                .andExpect(jsonPath("$[?(@.name == 'window-small')].algorithm").value("SLIDING_WINDOW"));

        mockMvc.perform(get("/api/v1/policies/algorithms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            authorizePayment(String apiKey, String idempotencyKey) {

        var request = post("/api/v1/payments/authorize")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": "ord-1", "amount": 149.99, "currency": "INR"}
                        """);
        return idempotencyKey == null ? request : request.header("Idempotency-Key", idempotencyKey);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            sendOtp(String apiKey) {

        return post("/api/v1/payments/otp")
                .header("X-API-Key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderId": "ord-1", "phone": "+919876543210"}
                        """);
    }
}
