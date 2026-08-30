package com.ayushjha.ratelimiter.config;

import com.ayushjha.ratelimiter.core.Algorithm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Externalised limits. Policies are data, not code, so quotas can be retuned
 * per environment without a rebuild.
 */
@Validated
@ConfigurationProperties(prefix = "ratelimiter")
public class RateLimiterProperties {

    /** Master switch; when false the filter admits everything. */
    private boolean enabled = true;

    /**
     * Behaviour when Redis cannot be reached. True admits traffic (availability
     * first, correct for a payment path); false rejects with 503 (hard enforcement).
     */
    private boolean failOpen = true;

    /** Namespace for every key this service writes. */
    @NotBlank
    private String keyPrefix = "rl";

    /** Policy applied when a caller does not name one. */
    @NotBlank
    private String defaultPolicy = "standard";

    @Valid
    private Identity identity = new Identity();

    /**
     * Request paths the servlet filter guards automatically.
     *
     * <p>{@code @Valid} sits on the type argument, not the container: validating the
     * elements is the intent, and annotating the container itself is deprecated.
     */
    private List<@Valid EnforcementRule> enforcement = new ArrayList<>();

    @NotNull
    private Map<String, @Valid Policy> policies = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isFailOpen() {
        return failOpen;
    }

    public void setFailOpen(boolean failOpen) {
        this.failOpen = failOpen;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }

    public String getDefaultPolicy() {
        return defaultPolicy;
    }

    public void setDefaultPolicy(String defaultPolicy) {
        this.defaultPolicy = defaultPolicy;
    }

    public Identity getIdentity() {
        return identity;
    }

    public void setIdentity(Identity identity) {
        this.identity = identity;
    }

    public List<EnforcementRule> getEnforcement() {
        return enforcement;
    }

    public void setEnforcement(List<EnforcementRule> enforcement) {
        this.enforcement = enforcement;
    }

    public Map<String, Policy> getPolicies() {
        return policies;
    }

    public void setPolicies(Map<String, Policy> policies) {
        this.policies = policies;
    }

    /** A single configured limit. */
    public static class Policy {

        @NotNull
        private Algorithm algorithm = Algorithm.TOKEN_BUCKET;

        /** Requests admitted per {@link #window}. */
        @Min(1)
        private long limit = 100;

        @NotNull
        private Duration window = Duration.ofSeconds(1);

        /**
         * Token bucket capacity - the largest instantaneous burst allowed.
         * Defaults to {@link #limit} (no burst headroom). Ignored by sliding window.
         */
        @Min(1)
        private Long burst;

        /** How long a request id is remembered for replay. */
        @NotNull
        private Duration idempotencyTtl = Duration.ofMinutes(5);

        private String description;

        public Algorithm getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(Algorithm algorithm) {
            this.algorithm = algorithm;
        }

        public long getLimit() {
            return limit;
        }

        public void setLimit(long limit) {
            this.limit = limit;
        }

        public Duration getWindow() {
            return window;
        }

        public void setWindow(Duration window) {
            this.window = window;
        }

        public Long getBurst() {
            return burst;
        }

        public void setBurst(Long burst) {
            this.burst = burst;
        }

        public Duration getIdempotencyTtl() {
            return idempotencyTtl;
        }

        public void setIdempotencyTtl(Duration idempotencyTtl) {
            this.idempotencyTtl = idempotencyTtl;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    /** Where the subject and the idempotency key are read from on inbound requests. */
    public static class Identity {

        @NotBlank
        private String apiKeyHeader = "X-API-Key";

        @NotBlank
        private String tenantHeader = "X-Tenant-Id";

        @NotBlank
        private String idempotencyHeader = "Idempotency-Key";

        /**
         * Only enable behind a load balancer you control. Otherwise a client can
         * spoof {@code X-Forwarded-For} and mint a fresh quota per request.
         */
        private boolean trustProxyHeaders = false;

        public String getApiKeyHeader() {
            return apiKeyHeader;
        }

        public void setApiKeyHeader(String apiKeyHeader) {
            this.apiKeyHeader = apiKeyHeader;
        }

        public String getTenantHeader() {
            return tenantHeader;
        }

        public void setTenantHeader(String tenantHeader) {
            this.tenantHeader = tenantHeader;
        }

        public String getIdempotencyHeader() {
            return idempotencyHeader;
        }

        public void setIdempotencyHeader(String idempotencyHeader) {
            this.idempotencyHeader = idempotencyHeader;
        }

        public boolean isTrustProxyHeaders() {
            return trustProxyHeaders;
        }

        public void setTrustProxyHeaders(boolean trustProxyHeaders) {
            this.trustProxyHeaders = trustProxyHeaders;
        }
    }

    /** Binds an Ant-style path pattern to a policy name. */
    public static class EnforcementRule {

        @NotBlank
        private String path;

        @NotBlank
        private String policy;

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public String getPolicy() {
            return policy;
        }

        public void setPolicy(String policy) {
            this.policy = policy;
        }
    }
}
