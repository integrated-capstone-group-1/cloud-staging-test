package com.capstone.gateway.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * API-RUS-01: Gateway Route Isolation Filter - Unit Tests
 *
 * Objective:
 * Verify GatewayRouteIsolationFilter correctly blocks /internal/** and direct mutation endpoints.
 *
 * Test Scenarios:
 * 1. /internal/** paths return 403 FORBIDDEN
 * 2. /api/accounts/{id}/credit endpoints return 403 FORBIDDEN
 * 3. /api/accounts/{id}/debit endpoints return 403 FORBIDDEN
 * 4. Public API endpoints (/api/v1/**) pass through to chain
 * 5. Filter order is HIGHEST_PRECEDENCE + 1
 */
@DisplayName("API-RUS-01: Gateway Route Isolation Filter Unit Tests")
public class GatewayRouteIsolationFilterTest {

    private GatewayRouteIsolationFilter filter;

    @Mock
    private ServerWebExchange exchange;

    @Mock
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        filter = new GatewayRouteIsolationFilter();
        when(chain.filter(any())).thenReturn(Mono.empty());
        
        // Mock the response with RETURNS_DEEP_STUBS so headers() returns a valid HttpHeaders mock
        var response = mock(org.springframework.http.server.reactive.ServerHttpResponse.class, RETURNS_DEEP_STUBS);
        when(exchange.getResponse()).thenReturn(response);
    }

    private void mockPath(String path) {
        var request = mock(org.springframework.http.server.reactive.ServerHttpRequest.class, RETURNS_DEEP_STUBS);
        when(request.getPath().value()).thenReturn(path);
        when(exchange.getRequest()).thenReturn(request);
    }

    @Test
    @DisplayName("Scenario 1: Block /internal/core/settlement with 403 FORBIDDEN")
    void shouldBlockInternalCoreSettlement() {
        mockPath("/internal/core/settlement");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Scenario 1: Block /internal/accounts/holds with 403 FORBIDDEN")
    void shouldBlockInternalAccounts() {
        mockPath("/internal/accounts/ACC-001/holds");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Scenario 1: Block /internal/batch/standing-instructions")
    void shouldBlockInternalBatch() {
        mockPath("/internal/batch/standing-instructions/execute");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Scenario 2: Block /api/accounts/ACC-001/credit with 403 FORBIDDEN")
    void shouldBlockDirectCreditEndpoint() {
        mockPath("/api/accounts/ACC-001/credit");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Scenario 3: Block /api/accounts/ACC-001/debit with 403 FORBIDDEN")
    void shouldBlockDirectDebitEndpoint() {
        mockPath("/api/accounts/ACC-001/debit");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Scenario 2: Block /api/accounts/12345/credit with different ID")
    void shouldBlockCreditEndpointDifferentId() {
        mockPath("/api/accounts/12345/credit");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Regression F-001: Block /api/v1/ledger/mutate with 403 FORBIDDEN")
    void shouldBlockLegacyLedgerMutateEndpoint() {
        mockPath("/api/v1/ledger/mutate");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Regression F-001: Block /api/v1/accounts/ACC-001/credit with 403 FORBIDDEN")
    void shouldBlockV1DirectCreditEndpoint() {
        mockPath("/api/v1/accounts/ACC-001/credit");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Regression F-001: Block /api/v1/accounts/ACC-001/debit with 403 FORBIDDEN")
    void shouldBlockV1DirectDebitEndpoint() {
        mockPath("/api/v1/accounts/ACC-001/debit");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        verify(chain, never()).filter(any());
    }

    @Test
    @DisplayName("Scenario 1: Block /internal/recon/trigger")
    void shouldBlockInternalReconciliation() {
        mockPath("/internal/recon/trigger");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("Scenario 4: Allow public /api/v1/accounts endpoint")
    void shouldAllowPublicApiEndpoint() {
        mockPath("/api/v1/accounts");
        filter.filter(exchange, chain);
        verify(chain, times(1)).filter(exchange);
        verify(exchange.getResponse(), never()).setStatusCode(any());
    }

    @Test
    @DisplayName("Scenario 4: Allow public /api/v1/payments endpoint")
    void shouldAllowPublicPaymentsEndpoint() {
        mockPath("/api/v1/payments");
        filter.filter(exchange, chain);
        verify(chain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("Scenario 1: Block /internal path without trailing slash")
    void shouldBlockInternalPathWithoutSlash() {
        mockPath("/internal");
        filter.filter(exchange, chain);
        verify(exchange.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("All blocked paths return 403")
    void shouldBlockAllInternalVariants() {
        String[] paths = {
                "/internal/recon/trigger",
                "/internal/risk/evaluate",
                "/internal/core/settlement/verify"
        };
        for (String path : paths) {
            ServerWebExchange ex = mock(ServerWebExchange.class);
            mockPathForExchange(ex, path);
            filter.filter(ex, chain);
            verify(ex.getResponse()).setStatusCode(HttpStatus.FORBIDDEN);
        }
    }

    private void mockPathForExchange(ServerWebExchange ex, String path) {
        var request = mock(org.springframework.http.server.reactive.ServerHttpRequest.class, RETURNS_DEEP_STUBS);
        var response = mock(org.springframework.http.server.reactive.ServerHttpResponse.class, RETURNS_DEEP_STUBS);
        when(request.getPath().value()).thenReturn(path);
        when(ex.getRequest()).thenReturn(request);
        when(ex.getResponse()).thenReturn(response);
    }

    @Test
    @DisplayName("Should not block /api/internal (public, not /internal)")
    void shouldNotBlockPublicPathsContainingInternal() {
        mockPath("/api/internal/something");
        filter.filter(exchange, chain);
        verify(chain, times(1)).filter(exchange);
    }

    @Test
    @DisplayName("Scenario 5: Filter order is HIGHEST_PRECEDENCE + 1")
    void filterOrderShouldBeHighestPrecedencePlus1() {
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, filter.getOrder());
    }
}
