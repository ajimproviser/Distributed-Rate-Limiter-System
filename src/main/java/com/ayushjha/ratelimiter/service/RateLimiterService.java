package com.ayushjha.ratelimiter.service;

import com.ayushjha.ratelimiter.config.RateLimiterProperties;
import com.ayushjha.ratelimiter.core.RateLimitContext;
import com.ayushjha.ratelimiter.core.RateLimitDecision;
import com.ayushjha.ratelimiter.core.RateLimitPolicy;
import com.ayushjha.ratelimiter.core.RateLimiterStrategy;
import com.ayushjha.ratelimiter.policy.PolicyCatalog;
import com.ayushjha.ratelimiter.strategy.RateLimiterStrategyRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Application-facing entry point: resolves the policy, dispatches to the right
 * strategy, records metrics, and decides what to do when Redis is unreachable.
 */
@Service
public class RateLimiterService {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterService.class);

    private static final String EVALUATION_TIMER = "ratelimiter.evaluation";
    private static final String DECISION_COUNTER = "ratelimiter.decisions";

    private final PolicyCatalog policies;
    private final RateLimiterStrategyRegistry strategies;
    private final RateLimiterProperties properties;
    private final MeterRegistry meters;

    public RateLimiterService(PolicyCatalog policies,
                              RateLimiterStrategyRegistry strategies,
                              RateLimiterProperties properties,
                              MeterRegistry meters) {
        this.policies = policies;
        this.strategies = strategies;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * Evaluates and consumes quota.
     *
     * @param requestId optional idempotency key; a repeat of the same id replays the
     *                  original verdict rather than consuming quota again
     */
    public RateLimitDecision check(String subject, String policyName, long cost, String requestId) {
        RateLimitPolicy policy = policies.resolve(policyName);
        return evaluate(new RateLimitContext(subject, policy, cost, requestId));
    }

    /** Reports current quota without consuming any. */
    public RateLimitDecision peek(String subject, String policyName) {
        RateLimitPolicy policy = policies.resolve(policyName);
        RateLimitContext context = new RateLimitContext(subject, policy, 0L, null);
        try {
            return strategies.forAlgorithm(policy.algorithm()).peek(context);
        } catch (DataAccessException ex) {
            return handleBackendFailure(context, ex);
        }
    }

    /** Restores a subject to full quota. Intended for support tooling, not the hot path. */
    public void reset(String subject, String policyName) {
        RateLimitPolicy policy = policies.resolve(policyName);
        strategies.forAlgorithm(policy.algorithm()).reset(subject, policy);
        log.info("Rate limit state reset for subject '{}' under policy '{}'", subject, policy.name());
    }

    private RateLimitDecision evaluate(RateLimitContext context) {
        RateLimitPolicy policy = context.policy();
        RateLimiterStrategy strategy = strategies.forAlgorithm(policy.algorithm());

        long startedAt = System.nanoTime();
        RateLimitDecision decision;
        try {
            decision = strategy.tryConsume(context);
        } catch (DataAccessException ex) {
            decision = handleBackendFailure(context, ex);
        }
        record(decision, System.nanoTime() - startedAt);
        return decision;
    }

    /**
     * A rate limiter must not become a single point of failure for the traffic it
     * protects. With fail-open enabled an unreachable Redis admits the request and
     * flags the decision as degraded so it is visible in metrics; with it disabled
     * the caller gets a 503 and no unmetered traffic slips through.
     */
    private RateLimitDecision handleBackendFailure(RateLimitContext context, DataAccessException cause) {
        if (!properties.isFailOpen()) {
            throw new RateLimiterUnavailableException(
                    "Rate limit backend unavailable and fail-open is disabled", cause);
        }
        log.warn("Rate limit backend unavailable for subject '{}' under policy '{}'; failing open",
                context.subject(), context.policy().name(), cause);
        return RateLimitDecision.failOpen(context);
    }

    private void record(RateLimitDecision decision, long elapsedNanos) {
        String outcome = decision.degraded() ? "degraded" : (decision.allowed() ? "allowed" : "blocked");

        Timer.builder(EVALUATION_TIMER)
                .description("Time to reach a rate limit decision, including the Redis round trip")
                .tag("policy", decision.policy())
                .tag("algorithm", decision.algorithm().name())
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);

        meters.counter(DECISION_COUNTER,
                        "policy", decision.policy(),
                        "algorithm", decision.algorithm().name(),
                        "outcome", outcome)
                .increment();
    }
}
