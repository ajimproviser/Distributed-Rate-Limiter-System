package com.ayushjha.ratelimiter.policy;

import java.util.Collection;
import java.util.List;

/** Raised when a caller names a policy that is not configured. */
public class UnknownPolicyException extends RuntimeException {

    private final String requestedPolicy;
    private final List<String> knownPolicies;

    public UnknownPolicyException(String requestedPolicy, Collection<String> knownPolicies) {
        super("Unknown rate limit policy '%s'. Known policies: %s".formatted(requestedPolicy, knownPolicies));
        this.requestedPolicy = requestedPolicy;
        this.knownPolicies = List.copyOf(knownPolicies);
    }

    public String getRequestedPolicy() {
        return requestedPolicy;
    }

    public List<String> getKnownPolicies() {
        return knownPolicies;
    }
}
