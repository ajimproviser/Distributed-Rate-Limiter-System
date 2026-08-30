package com.ayushjha.ratelimiter.web.filter;

import com.ayushjha.ratelimiter.config.RateLimiterProperties;
import com.ayushjha.ratelimiter.core.RateLimitDecision;
import com.ayushjha.ratelimiter.service.RateLimiterService;
import com.ayushjha.ratelimiter.web.RateLimitHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Enforces the configured limits on inbound HTTP traffic, rejecting excess with
 * {@code 429 Too Many Requests} before it reaches any business logic.
 *
 * <p>Ordered near the front of the chain deliberately: the point of a rate limiter
 * is to shed load early, so it must run before authentication, deserialisation or
 * anything else that costs real work on a request that is about to be dropped.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Infrastructure endpoints stay reachable even while a caller is being throttled. */
    private static final List<String> NEVER_LIMITED = List.of(
            "/actuator", "/swagger-ui", "/v3/api-docs", "/error", "/favicon.ico");

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final RateLimiterService rateLimiter;
    private final RateLimiterProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimiterService rateLimiter,
                           RateLimiterProperties properties,
                           ObjectMapper objectMapper) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        if (NEVER_LIMITED.stream().anyMatch(path::startsWith)) {
            return true;
        }
        return findPolicyFor(path) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String policy = findPolicyFor(request.getRequestURI());
        String subject = resolveSubject(request);
        String requestId = request.getHeader(properties.getIdentity().getIdempotencyHeader());

        RateLimitDecision decision = rateLimiter.check(subject, policy, 1L, requestId);
        RateLimitHeaders.apply(response, decision);

        if (decision.allowed()) {
            chain.doFilter(request, response);
            return;
        }

        log.debug("Throttled {} {} for subject '{}' under policy '{}', retry in {}ms",
                request.getMethod(), request.getRequestURI(), subject, decision.policy(),
                decision.retryAfterMillis());

        writeTooManyRequests(request, response, decision);
    }

    private String findPolicyFor(String path) {
        for (RateLimiterProperties.EnforcementRule rule : properties.getEnforcement()) {
            if (MATCHER.match(rule.getPath(), path)) {
                return rule.getPolicy();
            }
        }
        return null;
    }

    /**
     * Prefers the most specific identity available so quotas are per-caller rather
     * than shared: API key, then tenant, then the network address as a last resort.
     */
    private String resolveSubject(HttpServletRequest request) {
        RateLimiterProperties.Identity identity = properties.getIdentity();

        String apiKey = trimmed(request.getHeader(identity.getApiKeyHeader()));
        if (apiKey != null) {
            return "key:" + apiKey;
        }
        String tenant = trimmed(request.getHeader(identity.getTenantHeader()));
        if (tenant != null) {
            return "tenant:" + tenant;
        }
        return "ip:" + clientAddress(request, identity.isTrustProxyHeaders());
    }

    private static String clientAddress(HttpServletRequest request, boolean trustProxyHeaders) {
        if (trustProxyHeaders) {
            String forwarded = trimmed(request.getHeader("X-Forwarded-For"));
            if (forwarded != null) {
                // Left-most entry is the original client; the rest are proxy hops.
                int comma = forwarded.indexOf(',');
                return comma > 0 ? forwarded.substring(0, comma).trim() : forwarded;
            }
        }
        return request.getRemoteAddr();
    }

    private static String trimmed(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void writeTooManyRequests(HttpServletRequest request,
                                      HttpServletResponse response,
                                      RateLimitDecision decision) throws IOException {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS,
                "Rate limit exceeded for this subject. Retry after %d ms."
                        .formatted(decision.retryAfterMillis()));
        problem.setTitle("Too Many Requests");
        problem.setType(URI.create("https://httpstatuses.io/429"));
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("policy", decision.policy());
        problem.setProperty("algorithm", decision.algorithm().name());
        problem.setProperty("limit", decision.limit());
        problem.setProperty("remaining", decision.remaining());
        problem.setProperty("retryAfterMillis", decision.retryAfterMillis());

        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
