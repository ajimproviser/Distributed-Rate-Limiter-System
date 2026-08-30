package com.ayushjha.ratelimiter.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    private final String apiKeyHeader;

    public OpenApiConfig(RateLimiterProperties properties) {
        this.apiKeyHeader = properties.getIdentity().getApiKeyHeader();
    }

    @Bean
    public OpenAPI rateLimiterOpenApi(@Value("${server.port:8080}") int port) {
        return new OpenAPI()
                .info(new Info()
                        .title("Distributed Rate Limiter System")
                        .version("1.0.0")
                        .description("""
                                Payment gateway-scale rate limiting as a service.

                                Two interchangeable algorithms run entirely inside Redis as atomic Lua \
                                scripts, so any number of application instances enforce one shared quota \
                                with sub-millisecond overhead:

                                * **Token Bucket** - burst-tolerant, O(1) memory per subject. Use for \
                                high-volume quotas.
                                * **Sliding Window Log** - exact rolling window with no boundary burst. \
                                Use for small, security-sensitive limits.

                                Supplying an idempotency key makes enforcement replay-safe: a retried \
                                request returns its original verdict instead of consuming quota twice.
                                """)
                        .contact(new Contact().name("Ayush Jha").url("https://github.com/Ayushjha"))
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .servers(List.of(
                        new Server().url("http://localhost:" + port).description("Local instance")))
                .components(new Components()
                        .addSecuritySchemes("ApiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name(apiKeyHeader)
                                .description("Identifies the caller. Used as the rate limit subject on guarded paths.")));
    }
}
