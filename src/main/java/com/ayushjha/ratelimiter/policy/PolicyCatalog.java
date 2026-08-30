package com.ayushjha.ratelimiter.policy;

import com.ayushjha.ratelimiter.config.RateLimiterProperties;
import com.ayushjha.ratelimiter.core.RateLimitPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Singleton, immutable catalog of every configured policy.
 *
 * <p>Configuration is read once, validated, and reduced to precomputed
 * {@link RateLimitPolicy} records at startup. A malformed policy therefore fails
 * the boot with a message naming the offender, and the hot path does no parsing,
 * no division and no map mutation.
 */
@Component
public class PolicyCatalog {

    private static final Logger log = LoggerFactory.getLogger(PolicyCatalog.class);

    private final Map<String, RateLimitPolicy> policies;
    private final String defaultPolicyName;

    public PolicyCatalog(RateLimiterProperties properties) {
        Map<String, RateLimitPolicy> resolved = new LinkedHashMap<>();
        properties.getPolicies().forEach((name, policy) -> resolved.put(name, toPolicy(name, policy)));

        if (resolved.isEmpty()) {
            throw new IllegalStateException(
                    "No policies configured. Define at least one under 'ratelimiter.policies'.");
        }

        this.defaultPolicyName = properties.getDefaultPolicy();
        if (!resolved.containsKey(defaultPolicyName)) {
            throw new IllegalStateException(
                    "ratelimiter.default-policy '%s' is not defined. Known policies: %s"
                            .formatted(defaultPolicyName, resolved.keySet()));
        }

        this.policies = Collections.unmodifiableMap(resolved);
        log.info("Loaded {} rate limit policies (default '{}'): {}",
                policies.size(), defaultPolicyName, policies.keySet());
    }

    private static RateLimitPolicy toPolicy(String name, RateLimiterProperties.Policy source) {
        // Burst is optional: with no headroom configured, capacity equals the
        // sustained limit and the bucket behaves as a plain rate cap.
        long capacity = source.getBurst() != null ? source.getBurst() : source.getLimit();
        double refillPerMillis = (double) source.getLimit() / source.getWindow().toMillis();

        if (refillPerMillis <= 0d) {
            throw new IllegalStateException(
                    "Policy '%s' resolves to a zero refill rate; window %s is too long for limit %d"
                            .formatted(name, source.getWindow(), source.getLimit()));
        }

        return new RateLimitPolicy(
                name,
                source.getAlgorithm(),
                source.getLimit(),
                source.getWindow(),
                capacity,
                refillPerMillis,
                source.getIdempotencyTtl(),
                source.getDescription());
    }

    /** Resolves a policy by name, falling back to the configured default when blank. */
    public RateLimitPolicy resolve(String name) {
        String key = (name == null || name.isBlank()) ? defaultPolicyName : name;
        RateLimitPolicy policy = policies.get(key);
        if (policy == null) {
            throw new UnknownPolicyException(key, policies.keySet());
        }
        return policy;
    }

    public RateLimitPolicy defaultPolicy() {
        return policies.get(defaultPolicyName);
    }

    public Collection<RateLimitPolicy> all() {
        return policies.values();
    }
}
