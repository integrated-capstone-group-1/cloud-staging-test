package com.capstone.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Global Edge Security Filter: Internal Route Cloaking.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: GlobalFilter actively blocking external access to internal CBS and core balance mutation endpoints.
 * - HOW: Inspects request path. If it targets /internal/** or direct mutation endpoints
 *        (/api/accounts/{id}/credit, /api/accounts/{id}/debit), immediately terminates with 403 Forbidden.
 * - WHY: Enforces zero-trust defense-in-depth perimeter cloaking (T-005, CONTRACTS.md §1.1).
 *        External public/mobile clients must never directly trigger core credit/debit legs or internal settlement.
 *
 * TRACE:
 * Client Request -> GatewayRouteIsolationFilter -> [Matches internal route?]
 *   -> YES: Abort with 403 Forbidden (Never enters microservice mesh)
 *   -> NO:  Continue to downstream route
 */
@Component
@Slf4j
public class GatewayRouteIsolationFilter implements WebFilter, GlobalFilter, Ordered {

    private static final Pattern MUTATION_ROUTE_PATTERN = Pattern.compile("^/api(?:/v1)?/accounts/(?:[^/]+/)?(credit|debit)(?:/.*)?$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isBlockedInternalRoute(path)) {
            log.warn("Security Alert: External request attempted access to cloaked internal route: {}", path);

            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().add("X-Route-Error", "ACCESS_DENIED: Internal route cloaked at edge");
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isBlockedInternalRoute(path)) {
            log.warn("Security Alert: External request attempted access to cloaked internal route: {}", path);

            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            exchange.getResponse().getHeaders().add("X-Route-Error", "ACCESS_DENIED: Internal route cloaked at edge");
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    private boolean isBlockedInternalRoute(String path) {
        if (path == null) {
            return false;
        }

        // 1. Cloak all internal service-to-service endpoints (/internal/**)
        if (path.startsWith("/internal/") || "/internal".equals(path)) {
            return true;
        }

        // 2. Cloak direct accounts balance mutation endpoints (/api/accounts/*/credit, /api/accounts/*/debit, /api/v1/accounts/*)
        if (MUTATION_ROUTE_PATTERN.matcher(path).matches()) {
            return true;
        }

        // 3. Cloak legacy ledger adapter route (/api/v1/ledger/mutate)
        if ("/api/v1/ledger/mutate".equals(path) || path.startsWith("/api/v1/ledger/mutate/")) {
            return true;
        }

        return false;
    }

    @Override
    public int getOrder() {
        // Runs at HIGHEST_PRECEDENCE + 1 to block internal routes BEFORE route lookup/matching
        // This ensures /internal/** is blocked at the gateway edge, never reaching downstream services
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}

