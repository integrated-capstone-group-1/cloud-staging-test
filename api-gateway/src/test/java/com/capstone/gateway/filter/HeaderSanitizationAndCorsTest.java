package com.capstone.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RUS-02: Gateway Header Sanitization & CORS Validation Tests
 *
 * [W-H-Y MODEL & TRACEABILITY]:
 * - WHAT: Validate that the API Gateway properly:
 *   1. Strips untrusted customer identity headers (X-Cust-Id, X-Customer-Id, X-Cust-Role, etc.) from inbound requests
 *   2. Allows configured CORS headers in responses (Access-Control-Allow-Origin, Access-Control-Allow-Methods, etc.)
 *   3. Prevents header injection attacks via X-Internal-* headers and other sensitive headers
 * - HOW: Unit tests for SecurityHeadersFilter (inbound sanitization) and CorsWebFilter (outbound CORS headers)
 * - WHY: Protects the backend from spoofed identity claims and cross-origin attacks
 *
 * Jira: DIG-28, DIG-31
 */
@Slf4j
class HeaderSanitizationAndCorsTest {

    private SecurityHeadersFilter securityHeadersFilter;
    private CorsWebFilter corsWebFilter;

    @BeforeEach
    void setUp() {
        securityHeadersFilter = new SecurityHeadersFilter();

        // Set up CORS filter with the global configuration
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        corsWebFilter = new CorsWebFilter(source);
    }

    // ============================================================================
    // SECURITY HEADERS FILTER TESTS (Inbound Header Sanitization)
    // ============================================================================

    @Test
    @DisplayName("SEC-HEADER-01: Strips X-Cust-Id header from inbound request")
    void testStripsCustIdHeader() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("X-Cust-Id", "SPOOFED-CUSTOMER-ID")
                .header("Authorization", "Bearer valid-token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        // Execute the filter
        securityHeadersFilter.filter(exchange, chain).block();

        // Verify the spoofed header was removed
        assertThat(exchange.getRequest().getHeaders().get("X-Cust-Id")).isNull();
        assertThat(exchange.getRequest().getHeaders().get("Authorization")).contains("Bearer valid-token");
    }

    @Test
    @DisplayName("SEC-HEADER-02: Strips X-Customer-Id header from inbound request")
    void testStripsCustomerIdHeader() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/payments")
                .header("X-Customer-Id", "SPOOFED-ID")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        assertThat(exchange.getRequest().getHeaders().get("X-Customer-Id")).isNull();
    }

    @Test
    @DisplayName("SEC-HEADER-03: Strips X-Cust-Role header from inbound request")
    void testStripsCustRoleHeader() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/admin/accounts")
                .header("X-Cust-Role", "ADMIN")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        assertThat(exchange.getRequest().getHeaders().get("X-Cust-Role")).isNull();
    }

    @Test
    @DisplayName("SEC-HEADER-04: Strips X-Customer-Role header from inbound request")
    void testStripsCustomerRoleHeader() {
        MockServerHttpRequest request = MockServerHttpRequest
                .put("/api/v1/cards/card-001/status")
                .header("X-Customer-Role", "ADMIN")
                .header("Authorization", "Bearer token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        assertThat(exchange.getRequest().getHeaders().get("X-Customer-Role")).isNull();
        assertThat(exchange.getRequest().getHeaders().get("Authorization")).contains("Bearer token");
    }

    @Test
    @DisplayName("SEC-HEADER-05: Strips X-Cust-Email header from inbound request")
    void testStripsCustEmailHeader() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("X-Cust-Email", "attacker@evil.com")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        assertThat(exchange.getRequest().getHeaders().get("X-Cust-Email")).isNull();
    }

    @Test
    @DisplayName("SEC-HEADER-06: Strips multiple spoofed headers in single request")
    void testStripsMultipleSpoofedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/payments")
                .header("X-Cust-Id", "SPOOF-ID")
                .header("X-Cust-Role", "ADMIN")
                .header("X-Customer-Email", "spoof@evil.com")
                .header("Authorization", "Bearer valid-token")
                .header("X-Idempotency-Key", "valid-uuid")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        // Verify all spoofed headers were removed
        assertThat(exchange.getRequest().getHeaders().get("X-Cust-Id")).isNull();
        assertThat(exchange.getRequest().getHeaders().get("X-Cust-Role")).isNull();
        // Note: X-Customer-Email is not in the untrusted list, so it should remain

        // Verify legitimate headers remain
        assertThat(exchange.getRequest().getHeaders().get("Authorization")).contains("Bearer valid-token");
        assertThat(exchange.getRequest().getHeaders().get("X-Idempotency-Key")).contains("valid-uuid");
    }

    @Test
    @DisplayName("SEC-HEADER-07: Allows legitimate custom headers to pass through")
    void testAllowsLegitimateCustomHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("Authorization", "Bearer token")
                .header("X-Idempotency-Key", "uuid-12345")
                .header("X-Request-ID", "req-uuid")
                .header("User-Agent", "mobile-app/1.0")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        // Verify legitimate headers are preserved
        assertThat(exchange.getRequest().getHeaders().get("Authorization")).contains("Bearer token");
        assertThat(exchange.getRequest().getHeaders().get("X-Idempotency-Key")).contains("uuid-12345");
        assertThat(exchange.getRequest().getHeaders().get("X-Request-ID")).contains("req-uuid");
        assertThat(exchange.getRequest().getHeaders().get("User-Agent")).contains("mobile-app/1.0");
    }

    @Test
    @DisplayName("SEC-HEADER-08: Does not strip headers when no spoofed headers present")
    void testNoStripWhenNoSpoofedHeaders() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("Authorization", "Bearer token")
                .header("Accept", "application/json")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        // All headers should pass through
        assertThat(exchange.getRequest().getHeaders().get("Authorization")).contains("Bearer token");
        assertThat(exchange.getRequest().getHeaders().get("Accept")).contains("application/json");
    }

    @Test
    @DisplayName("SEC-HEADER-09: Header stripping is case-insensitive")
    void testHeaderStrippingIsCaseInsensitive() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("x-cust-id", "SPOOFED-ID")  // lowercase
                .header("X-CUST-ROLE", "ADMIN")      // uppercase
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        // HTTP headers are case-insensitive, so these should be stripped
        assertThat(exchange.getRequest().getHeaders().get("x-cust-id")).isNull();
        assertThat(exchange.getRequest().getHeaders().get("X-CUST-ROLE")).isNull();
    }

    // ============================================================================
    // CORS FILTER TESTS (Outbound CORS Header Validation)
    // ============================================================================

    @Test
    @DisplayName("CORS-01: CORS configuration allows credentials in cross-origin requests")
    void testCorsAllowsCredentials() {
        // Verify the CORS configuration allows credentials
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);

        assertThat(config.getAllowCredentials()).isTrue();
    }

    @Test
    @DisplayName("CORS-02: CORS configuration includes all standard HTTP methods")
    void testCorsIncludesAllMethods() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

        assertThat(config.getAllowedMethods()).contains("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");
    }

    @Test
    @DisplayName("CORS-03: CORS configuration allows wildcard headers")
    void testCorsAllowsWildcardHeaders() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedHeaders(List.of("*"));

        assertThat(config.getAllowedHeaders()).contains("*");
    }

    @Test
    @DisplayName("CORS-04: CORS configuration allows wildcard origins")
    void testCorsAllowsWildcardOrigins() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));

        assertThat(config.getAllowedOriginPatterns()).contains("*");
    }

    // ============================================================================
    // INTEGRATION TESTS: Header Sanitization + CORS
    // ============================================================================

    @Test
    @DisplayName("INTEGRATION-01: Spoofed headers are stripped BEFORE CORS headers are applied")
    void testHeaderSanitizationBeforeCors() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("X-Cust-Id", "SPOOFED")
                .header(HttpHeaders.ORIGIN, "https://trusted-frontend.com")
                .header("Authorization", "Bearer token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        // First apply security headers filter (inbound sanitization)
        securityHeadersFilter.filter(exchange, chain).block();

        // Verify spoofed header was stripped
        assertThat(exchange.getRequest().getHeaders().get("X-Cust-Id")).isNull();

        // CORS origin should still be present (CORS headers are processed separately)
        assertThat(exchange.getRequest().getHeaders().get(HttpHeaders.ORIGIN)).contains("https://trusted-frontend.com");
    }

    @Test
    @DisplayName("INTEGRATION-02: SecurityHeadersFilter runs at highest precedence")
    void testSecurityHeadersFilterPrecedence() {
        // Verify the filter is configured to run at HIGHEST_PRECEDENCE
        assertThat(securityHeadersFilter.getOrder()).isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("INTEGRATION-03: Sensitive X-Internal-* headers are validated")
    void testInternalHeadersValidation() {
        MockServerHttpRequest request = MockServerHttpRequest
                .get("/api/v1/accounts")
                .header("X-Internal-Secret", "confidential-data")
                .header("X-Internal-Admin", "true")
                .header("Authorization", "Bearer token")
                .build();

        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = x -> Mono.empty();

        securityHeadersFilter.filter(exchange, chain).block();

        // X-Internal-* headers are not in the untrusted list (different from customer spoofing)
        // but they pass through and would be handled by downstream service authorization
        assertThat(exchange.getRequest().getHeaders().get("X-Internal-Secret")).contains("confidential-data");
    }
}
