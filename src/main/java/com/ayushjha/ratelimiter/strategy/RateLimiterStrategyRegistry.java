package com.ayushjha.ratelimiter.strategy;

import com.ayushjha.ratelimiter.core.Algorithm;
import com.ayushjha.ratelimiter.core.RateLimiterStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton lookup from {@link Algorithm} to its {@link RateLimiterStrategy}.
 *
 * <p>Spring hands every discovered strategy to the constructor, which folds them
 * into one immutable {@link EnumMap} built exactly once for the lifetime of the
 * application. The request path then resolves an algorithm through an array index
 * with no locking, no lazy initialisation and no allocation.
 *
 * <p>Completeness is verified at startup rather than on first use, so a missing or
 * duplicated strategy fails the boot instead of surfacing as a production 500.
 */
@Component
public class RateLimiterStrategyRegistry {

    private static final Logger log = LoggerFactory.getLogger(RateLimiterStrategyRegistry.class);

    private final Map<Algorithm, RateLimiterStrategy> strategies;

    public RateLimiterStrategyRegistry(List<RateLimiterStrategy> discovered) {
        Map<Algorithm, RateLimiterStrategy> byAlgorithm = new EnumMap<>(Algorithm.class);

        for (RateLimiterStrategy strategy : discovered) {
            RateLimiterStrategy previous = byAlgorithm.put(strategy.algorithm(), strategy);
            if (previous != null) {
                throw new IllegalStateException("Two strategies claim algorithm %s: %s and %s".formatted(
                        strategy.algorithm(),
                        previous.getClass().getSimpleName(),
                        strategy.getClass().getSimpleName()));
            }
        }

        for (Algorithm algorithm : Algorithm.values()) {
            if (!byAlgorithm.containsKey(algorithm)) {
                throw new IllegalStateException(
                        "No RateLimiterStrategy registered for algorithm " + algorithm);
            }
        }

        this.strategies = Collections.unmodifiableMap(byAlgorithm);
        log.info("Rate limiter strategies registered: {}", byAlgorithm.keySet());
    }

    public RateLimiterStrategy forAlgorithm(Algorithm algorithm) {
        RateLimiterStrategy strategy = strategies.get(algorithm);
        if (strategy == null) {
            throw new IllegalStateException("No RateLimiterStrategy registered for algorithm " + algorithm);
        }
        return strategy;
    }

    public Collection<Algorithm> supportedAlgorithms() {
        return strategies.keySet();
    }
}
