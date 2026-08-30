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

/** Policy under test: {@code window-small} - 3 requests per rolling 2 seconds. */
class SlidingWindowRateLimiterIT extends AbstractRedisIntegrationTest {

    private static final String POLICY = "window-small";
    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofSeconds(2);

    @Autowired
    private RateLimiterService rateLimiter;

    @Autowired
    private SlidingWindowRateLimiter slidingWindow;

    @Autowired
    private PolicyCatalog policies;

    @Test
    @DisplayName("admits exactly the limit inside one window, then blocks")
    void admitsLimitThenBlocks() {
        String subject = uniqueSubject("window");

        for (int i = 1; i <= LIMIT; i++) {
            RateLimitDecision decision = rateLimiter.check(subject, POLICY, 1L, null);
            assertThat(decision.allowed()).as("request %d of %d", i, LIMIT).isTrue();
            assertThat(decision.remaining()).isEqualTo(LIMIT - i);
        }

        RateLimitDecision blocked = rateLimiter.check(subject, POLICY, 1L, null);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.remaining()).isZero();
        assertThat(blocked.retryAfterMillis()).isPositive();
        assertThat(blocked.algorithm()).isEqualTo(Algorithm.SLIDING_WINDOW);
    }

    @Test
    @DisplayName("stays blocked past the midpoint of the window, unlike a fixed-window counter")
    void doesNotResetOnAFixedBoundary() {
        String subject = uniqueSubject("no-boundary-burst");

        for (int i = 0; i < LIMIT; i++) {
            rateLimiter.check(subject, POLICY, 1L, null);
        }
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isFalse();

        // A fixed-window counter keyed to wall-clock buckets would have reset by now
        // and admitted a fresh full allowance. The trailing edge here moves with the
        // requests themselves, so the quota is still spent.
        sleep(WINDOW.dividedBy(2).plusMillis(100));

        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed())
                .as("quota must still be exhausted mid-window")
                .isFalse();
    }

    @Test
    @DisplayName("recovers once the earliest requests age out of the window")
    void recoversAfterWindowElapses() {
        String subject = uniqueSubject("aging");

        for (int i = 0; i < LIMIT; i++) {
            rateLimiter.check(subject, POLICY, 1L, null);
        }
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isFalse();

        await().atMost(WINDOW.plusSeconds(2))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> assertThat(
                        rateLimiter.check(subject, POLICY, 1L, null).allowed()).isTrue());
    }

    @Test
    @DisplayName("replays the original verdict for a repeated request id without double-charging")
    void isIdempotentForRepeatedRequestIds() {
        String subject = uniqueSubject("idempotent-window");
        String requestId = UUID.randomUUID().toString();

        RateLimitDecision first = rateLimiter.check(subject, POLICY, 1L, requestId);
        assertThat(first.allowed()).isTrue();
        assertThat(first.replayed()).isFalse();

        RateLimitDecision replay = rateLimiter.check(subject, POLICY, 1L, requestId);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.remaining()).isEqualTo(first.remaining());

        // Only one entry was ever recorded, so the remaining two still get through.
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isTrue();
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isTrue();
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isFalse();
    }

    @Test
    @DisplayName("peek reports state without recording a request")
    void peekDoesNotConsume() {
        String subject = uniqueSubject("peek-window");

        rateLimiter.check(subject, POLICY, 1L, null);
        for (int i = 0; i < 5; i++) {
            assertThat(rateLimiter.peek(subject, POLICY).remaining()).isEqualTo(LIMIT - 1);
        }
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("reset clears the window immediately")
    void resetClearsWindow() {
        String subject = uniqueSubject("reset-window");

        for (int i = 0; i < LIMIT; i++) {
            rateLimiter.check(subject, POLICY, 1L, null);
        }
        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isFalse();

        rateLimiter.reset(subject, POLICY);

        assertThat(rateLimiter.check(subject, POLICY, 1L, null).allowed()).isTrue();
    }

    @Test
    @DisplayName("admits no more than the limit under concurrent contention")
    void isAtomicUnderConcurrency() throws Exception {
        String subject = uniqueSubject("race-window");
        RateLimitPolicy policy = policies.resolve(POLICY);
        int threads = 200;

        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    try {
                        startGate.await();
                        if (slidingWindow.tryConsume(new RateLimitContext(subject, policy, 1L, null)).allowed()) {
                            allowed.incrementAndGet();
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }

            startGate.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(allowed.get())
                .as("%d concurrent requests against a limit of %d", threads, LIMIT)
                .isEqualTo(LIMIT);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the window to advance", ex);
        }
    }
}
