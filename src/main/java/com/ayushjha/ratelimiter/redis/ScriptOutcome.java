package com.ayushjha.ratelimiter.redis;

import java.util.List;

/**
 * Typed view of the five-element array both Lua scripts return. Parsed through
 * {@link Number} because the driver may hand back {@code Integer} or {@code Long}
 * depending on magnitude.
 */
public record ScriptOutcome(
        boolean allowed,
        long remaining,
        long retryAfterMillis,
        long resetAfterMillis,
        boolean replayed
) {

    private static final int EXPECTED_ELEMENTS = 5;

    public static ScriptOutcome from(List<?> raw) {
        if (raw == null || raw.size() < EXPECTED_ELEMENTS) {
            throw new IllegalStateException(
                    "Rate limit script returned an unexpected result: " + raw
                            + " (expected " + EXPECTED_ELEMENTS + " elements)");
        }
        return new ScriptOutcome(
                asLong(raw.get(0)) == 1L,
                asLong(raw.get(1)),
                asLong(raw.get(2)),
                asLong(raw.get(3)),
                asLong(raw.get(4)) == 1L);
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("Rate limit script returned a non-numeric element: " + value);
    }
}
