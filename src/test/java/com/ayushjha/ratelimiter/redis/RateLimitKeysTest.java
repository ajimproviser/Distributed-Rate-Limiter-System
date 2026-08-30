package com.ayushjha.ratelimiter.redis;

import com.ayushjha.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitKeysTest {

    private RateLimitKeys keys;

    @BeforeEach
    void setUp() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setKeyPrefix("rl");
        keys = new RateLimitKeys(properties);
    }

    @Test
    @DisplayName("namespaces keys and wraps the subject in a cluster hash tag")
    void buildsNamespacedKeys() {
        assertThat(keys.tokenBucketKey("merchant-1")).isEqualTo("rl:{merchant-1}:tb");
        assertThat(keys.slidingWindowKey("merchant-1")).isEqualTo("rl:{merchant-1}:sw");
        assertThat(keys.idempotencyKey("merchant-1", "req-9")).isEqualTo("rl:{merchant-1}:idem:req-9");
    }

    @Test
    @DisplayName("puts every key for one subject in the same hash slot")
    void keepsSubjectKeysInOneSlot() {
        String subject = "merchant-1";

        // Only the bracketed portion is hashed by Redis Cluster, so identical tags
        // guarantee a single slot and therefore a legal multi-key script.
        String tag = hashTagOf(keys.tokenBucketKey(subject));

        assertThat(hashTagOf(keys.slidingWindowKey(subject))).isEqualTo(tag);
        assertThat(hashTagOf(keys.idempotencyKey(subject, "req-9"))).isEqualTo(tag);
    }

    @Test
    @DisplayName("strips characters that would break the hash tag")
    void sanitizesUnsafeCharacters() {
        String key = keys.tokenBucketKey("ev{il}sub ject");

        assertThat(key).isEqualTo("rl:{ev_il_sub_ject}:tb");
        assertThat(hashTagOf(key)).isEqualTo("ev_il_sub_ject");
    }

    @Test
    @DisplayName("bounds subject length so a hostile header cannot bloat key memory")
    void truncatesOverlongSubjects() {
        String key = keys.tokenBucketKey("x".repeat(500));

        assertThat(hashTagOf(key)).hasSize(128);
    }

    @Test
    @DisplayName("uses a placeholder when no request id is supplied, keeping the slot stable")
    void usesPlaceholderForMissingRequestId() {
        String withoutId = keys.idempotencyKey("merchant-1", null);

        assertThat(withoutId).isEqualTo("rl:{merchant-1}:idem:-");
        assertThat(keys.idempotencyKey("merchant-1", "   ")).isEqualTo(withoutId);
    }

    private static String hashTagOf(String key) {
        return key.substring(key.indexOf('{') + 1, key.indexOf('}'));
    }
}
