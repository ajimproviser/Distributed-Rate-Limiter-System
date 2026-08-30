package com.ayushjha.ratelimiter.service;

/**
 * Raised when the Redis backend cannot be reached and {@code ratelimiter.fail-open}
 * is disabled, i.e. the operator chose hard enforcement over availability.
 */
public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
