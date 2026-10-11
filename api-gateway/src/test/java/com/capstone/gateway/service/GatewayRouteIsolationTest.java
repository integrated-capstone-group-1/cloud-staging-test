package com.capstone.gateway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API-RUS-01: Gateway Route Isolation - Block Internal `/internal/**` Routes
 *
 * Objective:
 * Verify that Spring Cloud Gateway blocks all external requests to `/internal/**` routes with 403 FORBIDDEN.
 * Only authenticated internal services can access these endpoints via service mesh.
 *
 * Test Scenarios:
 * 1. External POST to /internal/core/settlement returns 403 FORBIDDEN
 * 2. External POST to /internal/accounts/holds returns 403 FORBIDDEN
 * 3. External POST to /internal/batch/standing-instructions returns 403 FORBIDDEN
 * 4. Direct mutation endpoints (/api/accounts/{id}/credit) return 403 FORBIDDEN
 * 5. All internal routes consistently blocked with security headers
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DisplayName("API-RUS-01: Gateway Route Isolation & Internal Endpoint Blocking")
public class GatewayRouteIsolationTest {

    @Autowired
    private WebTestClient webTestClient;

    private static final String INTERNAL_SETTLEMENT_ENDPOINT = "/internal/core/settlement";
    private static final String INTERNAL_HOLDS_ENDPOINT = "/internal/accounts/ACC-001/holds";
    private static final String INTERNAL_BATCH_ENDPOINT = "/internal/batch/standing-instructions/execute";
    private static final String MUTATION_CREDIT_ENDPOINT = "/api/accounts/ACC-001/credit";
    private static final String MUTATION_DEBIT_ENDPOINT = "/api/accounts/ACC-001/debit";

    @BeforeEach
    void setUp() {
        // Gateway is configured to block all /internal/** routes from external clients
        // GatewayRouteIsolationFilter returns 403 FORBIDDEN for blocked routes
        // Filter runs at HIGHEST_PRECEDENCE + 1 to block before route lookup
    }

    @Test
    @DisplayName("Should return 403 FORBIDDEN for external POST to /internal/core/settlement")
    void shouldBlockExternalPostToInternalSettlementEndpoint() {
        // Arrange
        String requestBody = """
                {
                  "accountIds": ["ACC-001", "ACC-002"],
                  "amount": "1000.0000",
                  "currency": "PHP"
                }
                """;

        // Act & Assert - Gateway filter returns 403 BEFORE route lookup
        webTestClient.post()
                .uri(INTERNAL_SETTLEMENT_ENDPOINT)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Route-Error")
                .expectHeader().value("X-Route-Error", v -> assertTrue(v.contains("ACCESS_DENIED")));
    }

    @Test
    @DisplayName("Should return 403 FORBIDDEN for external POST to /internal/accounts/{id}/holds")
    void shouldBlockExternalPostToInternalHoldsEndpoint() {
        // Arrange
        String requestBody = """
                {
                  "amount": "500.0000",
                  "currency": "PHP",
                  "holdType": "SETTLEMENT_CLEARANCE",
                  "expiresInHours": 24,
                  "reason": "Manual Hold"
                }
                """;

        // Act & Assert
        webTestClient.post()
                .uri(INTERNAL_HOLDS_ENDPOINT)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Route-Error");
    }

    @Test
    @DisplayName("Should return 403 FORBIDDEN for external POST to /internal/batch/standing-instructions/execute")
    void shouldBlockExternalPostToInternalBatchEndpoint() {
        // Act & Assert
        webTestClient.post()
                .uri(INTERNAL_BATCH_ENDPOINT)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Route-Error");
    }

    @Test
    @DisplayName("Should block direct mutation endpoints /api/accounts/{id}/credit and /api/accounts/{id}/debit")
    void shouldBlockDirectMutationEndpoints() {
        // Arrange
        String requestBody = """
                {
                  "amount": "100.0000",
                  "currency": "PHP"
                }
                """;

        // Act & Assert - Credit endpoint blocked
        webTestClient.post()
                .uri(MUTATION_CREDIT_ENDPOINT)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Route-Error");

        // Act & Assert - Debit endpoint blocked
        webTestClient.post()
                .uri(MUTATION_DEBIT_ENDPOINT)
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().exists("X-Route-Error");
    }

    @Test
    @DisplayName("Should enforce 403 on all /internal/* path variations")
    void shouldBlockAllInternalPathVariations() {
        // Test multiple internal path patterns - all should be blocked with 403
        String[] internalPaths = {
                "/internal/accounts",
                "/internal/core/settlement",
                "/internal/batch/standing-instructions/execute",
                "/internal/recon/trigger",
                "/internal/risk/evaluate"
        };

        for (String path : internalPaths) {
            webTestClient.post()
                    .uri(path)
                    .header("Content-Type", "application/json")
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().exists("X-Route-Error");
        }
    }

    @Test
    @DisplayName("Should verify security header present on blocked requests")
    void shouldReturnSecurityHeaderOnBlockedRequests() {
        // Act & Assert - Verify X-Route-Error header indicates access denied
        webTestClient.post()
                .uri(INTERNAL_SETTLEMENT_ENDPOINT)
                .header("Content-Type", "application/json")
                .bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().value("X-Route-Error", v -> {
                    assertTrue(v.contains("ACCESS_DENIED"), "Header should contain ACCESS_DENIED");
                    assertTrue(v.contains("Internal"), "Header should indicate internal route");
                });
    }

    @Test
    @DisplayName("Should allow public API endpoint /api/v1/accounts (but may require auth)")
    void shouldAllowPublicApiEndpoint() {
        // Act - Public API endpoint should NOT return 403 (may return 401, 404, or 200)
        webTestClient.get()
                .uri("/api/v1/accounts")
                .exchange()
                .expectStatus().value(status -> {
                    // NOT forbidden - can be 401 (no auth), 404 (not found), or 200 (success)
                    assertTrue(status != 403, "Public API should not return 403 Forbidden");
                });
    }

    @Test
    @DisplayName("Should consistently block internal routes across multiple requests")
    void shouldConsistentlyBlockInternalRoutes() {
        // Fire 5 rapid requests to internal endpoint
        // All should return 403, verifying consistent enforcement
        for (int i = 0; i < 5; i++) {
            webTestClient.post()
                    .uri(INTERNAL_SETTLEMENT_ENDPOINT)
                    .header("Content-Type", "application/json")
                    .bodyValue("{}")
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().exists("X-Route-Error");
        }
    }
}
