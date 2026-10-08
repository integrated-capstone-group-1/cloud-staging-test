package com.capstone.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Global Edge Security Filter: Inbound Header Sanitization.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: GlobalFilter stripping any untrusted customer identity headers from incoming HTTP requests.
 * - HOW: Inspects incoming headers at highest gateway precedence and strips X-Cust-Id, X-Cust-Role,
 *        X-Cust-Email, X-Customer-Id, and X-Customer-Role.
 * - WHY: Prevents header injection and privilege spoofing (BR-03, T-005). Downstream microservices must
 *        only trust identity headers injected by the Gateway's verified JWT filter.
 *
 * TRACE:
 * Client Request -> SecurityHeadersFilter (Strips untrusted headers) -> JwtAuthenticationFilter (Injects verified claims)
 */
@Component
@Slf4j
public class SecurityHeadersFilter implements GlobalFilter, Ordered {

    private static final List<String> UNTRUSTED_HEADERS = List.of(
            "X-Cust-Id",
            "X-Cust-Role",
            "X-Cust-Email",
            "X-Customer-Id",
            "X-Customer-Role"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        boolean hasUntrusted = UNTRUSTED_HEADERS.stream()
                .anyMatch(h -> request.getHeaders().containsKey(h));

        if (hasUntrusted) {
            log.warn("Sanitizing spoofed/untrusted identity headers from incoming request: URI={}", request.getURI());

            ServerHttpRequest sanitizedRequest = request.mutate()
                    .headers(httpHeaders -> {
                        for (String headerName : UNTRUSTED_HEADERS) {
                            httpHeaders.remove(headerName);
                        }
                    })
                    .build();

            return chain.filter(exchange.mutate().request(sanitizedRequest).build());
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Runs at highest precedence before authentication and route filters
        return Ordered.HIGHEST_PRECEDENCE;
    }
}

