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
import java.util.UUID;

/**
 * Sliding window log backed by one sorted set per subject.
 *
 * <p>Unlike a fixed-window counter, this cannot be gamed at a window boundary: a
 * caller who spends the full limit just before a boundary and again just after
 * would double the intended rate under a fixed counter, but here the trailing edge
 * moves continuously, so the limit holds over every possible window position.
 *
 * <p>The trade is memory - one member per in-window request - so it is reserved
 * for small limits where exactness is worth more than footprint.
 */
@Component
public class SlidingWindowRateLimiter implements RateLimiterStrategy {

    private final StringRedisTemplate redis;
    private final RedisScript<List<Long>> script;
    private final RateLimitKeys keys;

    public SlidingWindowRateLimiter(StringRedisTemplate redis,
                                    @Qualifier("slidingWindowScript") RedisScript<List<Long>> script,
                                    RateLimitKeys keys) {
        this.redis = redis;
        this.script = script;
        this.keys = keys;
    }

    @Override
    public Algorithm algorithm() {
        return Algorithm.SLIDING_WINDOW;
    }

    @Override
    public RateLimitDecision tryConsume(RateLimitContext context) {
        RateLimitPolicy policy = context.policy();
        boolean useIdempotency = context.hasRequestId() && !context.isProbe();

        // Members must be unique or a second request would overwrite the first's
        // entry instead of adding to the window. A caller-supplied id doubles as
        // the dedupe token; otherwise generate one.
        String member = context.hasRequestId() ? context.requestId() : UUID.randomUUID().toString();

        List<String> scriptKeys = List.of(
                keys.slidingWindowKey(context.subject()),
                keys.idempotencyKey(context.subject(), context.requestId()));

        List<Long> raw = redis.execute(
                script,
                scriptKeys,
                Long.toString(policy.limit()),
                Long.toString(policy.window().toMillis()),
                Long.toString(context.cost()),
                member,
                Long.toString(policy.idempotencyTtl().toMillis()),
                useIdempotency ? "1" : "0");

        ScriptOutcome outcome = ScriptOutcome.from(raw);
        return new RateLimitDecision(
                outcome.allowed(),
                context.subject(),
                policy.name(),
                Algorithm.SLIDING_WINDOW,
                policy.limit(),
                outcome.remaining(),
                outcome.retryAfterMillis(),
                outcome.resetAfterMillis(),
                outcome.replayed(),
                false);
    }

    @Override
    public void reset(String subject, RateLimitPolicy policy) {
        redis.delete(keys.slidingWindowKey(subject));
    }
}
