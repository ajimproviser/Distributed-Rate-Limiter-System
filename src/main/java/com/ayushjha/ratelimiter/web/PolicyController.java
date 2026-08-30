package com.ayushjha.ratelimiter.web;

import com.ayushjha.ratelimiter.core.Algorithm;
import com.ayushjha.ratelimiter.policy.PolicyCatalog;
import com.ayushjha.ratelimiter.strategy.RateLimiterStrategyRegistry;
import com.ayushjha.ratelimiter.web.dto.PolicyView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping(value = "/api/v1/policies", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Policies", description = "Discover the configured limits and available algorithms")
public class PolicyController {

    private final PolicyCatalog policies;
    private final RateLimiterStrategyRegistry strategies;

    public PolicyController(PolicyCatalog policies, RateLimiterStrategyRegistry strategies) {
        this.policies = policies;
        this.strategies = strategies;
    }

    @GetMapping
    @Operation(summary = "List every configured policy")
    public List<PolicyView> list() {
        return policies.all().stream().map(PolicyView::from).toList();
    }

    @GetMapping("/{name}")
    @Operation(summary = "Fetch one policy by name")
    public PolicyView get(@PathVariable String name) {
        return PolicyView.from(policies.resolve(name));
    }

    @GetMapping("/algorithms")
    @Operation(summary = "List algorithms this build supports")
    public Collection<Algorithm> algorithms() {
        return strategies.supportedAlgorithms();
    }
}
