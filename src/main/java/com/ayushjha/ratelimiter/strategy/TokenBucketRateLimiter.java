package com.ayushjha.ratelimiter.strategy;

import com.ayushjha.ratelimiter.core.Algorithm;
import com.ayushjha.ratelimiter.core.RateLimitContext;
import com.ayushjha.ratelimiter.core.RateLimitDecision;
import com.ayushjha.ratelimiter.core.RateLimitPolicy;
import com.ayushjha.ratelimiter.core.RateLimiterStrategy;
import com.ayushjha.ratelimiter.redis.RateLimitKeys;
import com.ayushjha.ratelimiter.redis.ScriptOutcome;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Token bucket backed by a single Redis hash per subject and one atomic script.
 *
 * <p>Two fields - remaining tokens and the last touch timestamp - are enough to
 * reconstruct the bucket at any later moment, so refill is computed lazily on read
 * instead of by a sweeper. That keeps the cost of a decision at one hash read and
 * one hash write regardless of traffic volume, which is why this is the algorithm
 * for six-figure-per-minute quotas.
 */
@Component
public class TokenBucketRateLimiter implements RateLimiterStrategy {

    private final StringRedisTemplate redis;
    private final RedisScript<List<Long>> script;
    private final RateLimitKeys keys;

    public TokenBucketRateLimiter(StringRedisTemplate redis,
                                  @Qualifier("tokenBucketScript") RedisScript<List<Long>> script,
                                  RateLimitKeys keys) {
        this.redis = redis;
        this.script = script;
        this.keys = keys;
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.TOKEN_BUCKET;
    }

    @Override
    public RateLimitDecision tryConsume(RateLimitContext context) {
        RateLimitPolicy policy = context.policy();
        boolean useIdempotency = context.hasRequestId() && !context.isProbe();

        List<String> scriptKeys = List.of(
                keys.tokenBucketKey(context.subject()),
                keys.idempotencyKey(context.subject(), context.requestId()));

        List<Long> raw = redis.execute(
                script,
                scriptKeys,
                Long.toString(policy.capacity()),
                policy.refillRateAsPlainString(),
                Long.toString(context.cost()),
                Long.toString(policy.idempotencyTtl().toMillis()),
                useIdempotency ? "1" : "0");

        ScriptOutcome outcome = ScriptOutcome.from(raw);
        return new RateLimitDecision(
                outcome.allowed(),
                context.subject(),
                policy.name(),
                Algorithm.TOKEN_BUCKET,
                policy.capacity(),
                outcome.remaining(),
                outcome.retryAfterMillis(),
                outcome.resetAfterMillis(),
                outcome.replayed(),
                false);
    }

    /**
     * Drops the bucket so the next request sees a full one. Idempotency markers are
     * intentionally left to expire on their own TTL - clearing them would let a
     * replayed request consume quota a second time.
     */
    @Override
    public void reset(String subject, RateLimitPolicy policy) {
        redis.delete(keys.tokenBucketKey(subject));
    }
}
