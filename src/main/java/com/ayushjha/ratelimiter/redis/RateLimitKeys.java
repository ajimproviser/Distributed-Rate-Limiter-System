package com.ayushjha.ratelimiter.redis;

import com.ayushjha.ratelimiter.config.RateLimiterProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Single source of truth for key layout: {@code <prefix>:{<subject>}:<suffix>}.
 *
 * <p>The braces are a Redis Cluster hash tag. Only the bracketed part is hashed,
 * so a subject's bucket and its idempotency markers always land in the same slot
 * and can be touched by one atomic script. Without the tag, every multi-key
 * script would be rejected by a clustered deployment.
 */
@Component
public class RateLimitKeys {

    private static final String TOKEN_BUCKET_SUFFIX = "tb";
    private static final String SLIDING_WINDOW_SUFFIX = "sw";
    private static final String IDEMPOTENCY_SUFFIX = "idem";

    /** Placeholder so the script always receives a same-slot second key. */
    private static final String NO_REQUEST_ID = "-";

    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._:@=-]");
    private static final int MAX_SEGMENT_LENGTH = 128;

    private final String prefix;

    public RateLimitKeys(RateLimiterProperties properties) {
        this.prefix = properties.getKeyPrefix();
    }

    public String tokenBucketKey(String subject) {
        return key(subject, TOKEN_BUCKET_SUFFIX);
    }

    public String slidingWindowKey(String subject) {
        return key(subject, SLIDING_WINDOW_SUFFIX);
    }

    public String idempotencyKey(String subject, String requestId) {
        String id = (requestId == null || requestId.isBlank()) ? NO_REQUEST_ID : sanitize(requestId);
        return key(subject, IDEMPOTENCY_SUFFIX + ":" + id);
    }

    private String key(String subject, String suffix) {
        return prefix + ":{" + sanitize(subject) + "}:" + suffix;
    }

    /**
     * Strips characters that would break the hash tag or make keys hard to read in
     * {@code redis-cli}, and bounds length so a hostile header cannot bloat memory.
     */
    private String sanitize(String value) {
        String cleaned = UNSAFE.matcher(value.trim()).replaceAll("_");
        return cleaned.length() <= MAX_SEGMENT_LENGTH ? cleaned : cleaned.substring(0, MAX_SEGMENT_LENGTH);
    }
}
