package com.ayushjha.ratelimiter.strategy;

import com.ayushjha.ratelimiter.core.Algorithm;
import com.ayushjha.ratelimiter.core.RateLimitContext;
import com.ayushjha.ratelimiter.core.RateLimitDecision;
import com.ayushjha.ratelimiter.core.RateLimitPolicy;
import com.ayushjha.ratelimiter.policy.PolicyCatalog;
import com.ayushjha.ratelimiter.service.RateLimiterService;
import com.ayushjha.ratelimiter.support.AbstractRedisIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TokenBucketRateLimiterIT extends AbstractRedisIntegrationTest {

    private static final String FAST_POLICY = "bucket-small";      // 5 tokens, one back every 200ms
    private static final String SLOW_POLICY = "bucket-slow";       // 2 tokens, one back every 5s
    private static final String METERED_POLICY = "bucket-metered"; // 10 tokens, one back every 6s

    @Autowired
    private RateLimiterService rateLimiter;

    @Autowired
    private TokenBucketRateLimiter tokenBucket;

    @Autowired
    private PolicyCatalog policies;

    @Test
    @DisplayName("admits exactly the bucket capacity, then blocks with a retry hint")
    void admitsCapacityThenBlocks() {
        String subject = uniqueSubject("capacity");

        for (int i = 1; i <= 2; i++) {
            assertThat(rateLimiter.check(subject, SLOW_POLICY, 1L, null).allowed())
                    .as("request %d fits inside a 2-token bucket", i)
                    .isTrue();
        }

        RateLimitDecision blocked = rateLimiter.check(subject, SLOW_POLICY, 1L, null);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterMillis()).isPositive();
        assertThat(blocked.algorithm()).isEqualTo(Algorithm.TOKEN_BUCKET);
    }

    @Test
    @DisplayName("reports decreasing remaining quota as tokens are spent")
    void reportsRemainingQuota() {
        String subject = uniqueSubject("remaining");

        assertThat(rateLimiter.check(subject, METERED_POLICY, 1L, null).remaining()).isEqualTo(9);
        assertThat(rateLimiter.check(subject, METERED_POLICY, 1L, null).remaining()).isEqualTo(8);
        assertThat(rateLimiter.check(subject, METERED_POLICY, 1L, null).limit()).isEqualTo(10);
    }

    @Test
    @DisplayName("refills continuously, so an exhausted bucket recovers on its own")
    void refillsOverTime() {
        String subject = uniqueSubject("refill");

        for (int i = 0; i < 5; i++) {
            rateLimiter.check(subject, FAST_POLICY, 1L, null);
        }
        assertThat(rateLimiter.check(subject, FAST_POLICY, 1L, null).allowed()).isFalse();

        // At 5 tokens/second a single token is back within ~200ms.
        await().atMost(Duration.ofSeconds(2))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(
                        rateLimiter.check(subject, FAST_POLICY, 1L, null).allowed()).isTrue());
    }

    @Test
    @DisplayName("keeps quotas independent per subject")
    void isolatesSubjects() {
        String exhausted = uniqueSubject("noisy");
        String untouched = uniqueSubject("quiet");

        for (int i = 0; i < 4; i++) {
            rateLimiter.check(exhausted, SLOW_POLICY, 1L, null);
        }
        assertThat(rateLimiter.check(exhausted, SLOW_POLICY, 1L, null).allowed()).isFalse();
        assertThat(rateLimiter.check(untouched, SLOW_POLICY, 1L, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("charges weighted cost for expensive operations")
    void chargesWeightedCost() {
        String subject = uniqueSubject("weighted");

        RateLimitDecision first = rateLimiter.check(subject, METERED_POLICY, 3L, null);
        assertThat(first.allowed()).isTrue();
        assertThat(first.remaining()).isEqualTo(7);

        // Only 7 tokens remain, so an 8-token request cannot be satisfied.
        assertThat(rateLimiter.check(subject, METERED_POLICY, 8L, null).allowed()).isFalse();
    }

    @Test
    @DisplayName("replays the original verdict for a repeated request id without double-charging")
    void isIdempotentForRepeatedRequestIds() {
        String subject = uniqueSubject("idempotent");
        String requestId = UUID.randomUUID().toString();

        RateLimitDecision first = rateLimiter.check(subject, SLOW_POLICY, 1L, requestId);
        assertThat(first.allowed()).isTrue();
        assertThat(first.replayed()).isFalse();
        assertThat(first.remaining()).isEqualTo(1);

        for (int retry = 0; retry < 5; retry++) {
            RateLimitDecision replay = rateLimiter.check(subject, SLOW_POLICY, 1L, requestId);
            assertThat(replay.allowed()).isTrue();
            assertThat(replay.replayed()).as("retry %d should be recognised as a replay", retry).isTrue();
            assertThat(replay.remaining()).as("a replay must not consume quota").isEqualTo(1);
        }

        // The single charged request left one token, so exactly one more gets through.
        assertThat(rateLimiter.check(subject, SLOW_POLICY, 1L, null).allowed()).isTrue();
        assertThat(rateLimiter.check(subject, SLOW_POLICY, 1L, null).allowed()).isFalse();
    }

    @Test
    @DisplayName("peek reports state without consuming quota")
    void peekDoesNotConsume() {
        String subject = uniqueSubject("peek");

        rateLimiter.check(subject, SLOW_POLICY, 1L, null);
        for (int i = 0; i < 5; i++) {
            RateLimitDecision probe = rateLimiter.peek(subject, SLOW_POLICY);
            assertThat(probe.remaining()).isEqualTo(1);
            assertThat(probe.allowed()).isTrue();
        }
        assertThat(rateLimiter.check(subject, SLOW_POLICY, 1L, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("reset restores a blocked subject to full quota")
    void resetRestoresQuota() {
        String subject = uniqueSubject("reset");

        rateLimiter.check(subject, SLOW_POLICY, 2L, null);
        assertThat(rateLimiter.check(subject, SLOW_POLICY, 1L, null).allowed()).isFalse();

        rateLimiter.reset(subject, SLOW_POLICY);

        RateLimitDecision afterReset = rateLimiter.check(subject, SLOW_POLICY, 1L, null);
        assertThat(afterReset.allowed()).isTrue();
        assertThat(afterReset.remaining()).isEqualTo(1);
    }

    @Test
    @DisplayName("admits no more than capacity under concurrent contention")
    void isAtomicUnderConcurrency() throws Exception {
        String subject = uniqueSubject("race");
        RateLimitPolicy policy = policies.resolve(SLOW_POLICY);
        int threads = 200;

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        if (tokenBucket.tryConsume(new RateLimitContext(subject, policy, 1L, null)).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            // Release every thread at once: a read-modify-write split across round
            // trips would over-admit here, which is exactly what the Lua script prevents.
            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get())
                .as("%d concurrent requests against a 2-token bucket", threads)
                .isEqualTo(2);
    }
}
