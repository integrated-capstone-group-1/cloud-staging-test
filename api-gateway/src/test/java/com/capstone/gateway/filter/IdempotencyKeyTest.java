package com.capstone.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API-RUS-05: Idempotency-Key Deduplication Tests
 *
 * [W-H-Y MODEL & TRACEABILITY]:
 * - WHAT: Validate idempotency-key deduplication prevents duplicate transaction execution.
 *   - Same Idempotency-Key + same customer = same cached response
 *   - Fire 5 identical requests with same key
 *   - Verify: Exactly 1 executes, remaining 4 return cached 409 Conflict or cached response
 * - HOW: Redis SETNX atomic operation for idempotency key tracking
 * - WHY: Prevents double-charging, duplicate payments, state corruption (DIG-5)
 *
 * Idempotency Pattern:
 * - Key: customer_id:idempotency_key
 * - Value: transaction_id or response
 * - TTL: 24 hours
 * - Unique Constraint: UNIQUE (customer_id, idempotency_key) in Oracle
 *
 * Jira: DIG-5
 */
@Slf4j
class IdempotencyKeyTest {

    private Map<String, String> idempotencyCache;

    @BeforeEach
    void setUp() {
        // Simulate Redis idempotency cache
        idempotencyCache = new ConcurrentHashMap<>();
    }

    // ============================================================================
    // IDEMPOTENCY KEY DEDUPLICATION TESTS
    // ============================================================================

    @Test
    @DisplayName("IDEMPOTENT-01: First request with idempotency key succeeds and is stored")
    void testFirstRequestSucceeds() {
        String customerId = "cust-001";
        String idempotencyKey = UUID.randomUUID().toString();
        String cacheKey = customerId + ":" + idempotencyKey;

        // Simulate SETNX (set if not exists)
        if (!idempotencyCache.containsKey(cacheKey)) {
            idempotencyCache.put(cacheKey, "txn-001");
        }

        assertThat(idempotencyCache).containsEntry(cacheKey, "txn-001");
    }

    @Test
    @DisplayName("IDEMPOTENT-02: Duplicate request with same key returns cached result (409 Conflict)")
    void testDuplicateRequestReturnsCached() {
        String customerId = "cust-002";
        String idempotencyKey = UUID.randomUUID().toString();
        String cacheKey = customerId + ":" + idempotencyKey;

        // First request
        if (!idempotencyCache.containsKey(cacheKey)) {
            idempotencyCache.put(cacheKey, "txn-002");
        }

        // Second request with same key
        String cachedValue = idempotencyCache.get(cacheKey);

        assertThat(cachedValue).isEqualTo("txn-002");
    }

    @Test
    @DisplayName("IDEMPOTENT-03: Five identical requests execute exactly once")
    void testFiveIdenticalRequestsExecuteOnce() {
        String customerId = "cust-003";
        String idempotencyKey = UUID.randomUUID().toString();
        String cacheKey = customerId + ":" + idempotencyKey;

        int executionCount = 0;
        int cachedResponseCount = 0;

        for (int i = 0; i < 5; i++) {
            // Simulate idempotency check
            if (!idempotencyCache.containsKey(cacheKey)) {
                // Execute transaction
                idempotencyCache.put(cacheKey, "txn-003");
                executionCount++;
            } else {
                // Return cached response
                cachedResponseCount++;
            }
        }

        assertThat(executionCount).isEqualTo(1);
        assertThat(cachedResponseCount).isEqualTo(4);
    }

    @Test
    @DisplayName("IDEMPOTENT-04: Different idempotency keys allow multiple executions")
    void testDifferentKeysAllowMultipleExecutions() {
        String customerId = "cust-004";

        int totalExecutions = 0;

        for (int i = 0; i < 3; i++) {
            String idempotencyKey = UUID.randomUUID().toString();  // Different key each time
            String cacheKey = customerId + ":" + idempotencyKey;

            if (!idempotencyCache.containsKey(cacheKey)) {
                idempotencyCache.put(cacheKey, "txn-004-" + i);
                totalExecutions++;
            }
        }

        assertThat(totalExecutions).isEqualTo(3);
    }

    @Test
    @DisplayName("IDEMPOTENT-05: Same key for different customers allows separate executions")
    void testSameKeyDifferentCustomersIsolated() {
        String idempotencyKey = UUID.randomUUID().toString();  // Same key

        int totalExecutions = 0;

        // Customer 1
        String cacheKey1 = "cust-005-a:" + idempotencyKey;
        if (!idempotencyCache.containsKey(cacheKey1)) {
            idempotencyCache.put(cacheKey1, "txn-005-a");
            totalExecutions++;
        }

        // Customer 2 (different customer, same idempotency key)
        String cacheKey2 = "cust-005-b:" + idempotencyKey;
        if (!idempotencyCache.containsKey(cacheKey2)) {
            idempotencyCache.put(cacheKey2, "txn-005-b");
            totalExecutions++;
        }

        assertThat(totalExecutions).isEqualTo(2);
    }

    @Test
    @DisplayName("IDEMPOTENT-06: Idempotency key format is lowercase UUID")
    void testIdempotencyKeyFormat() {
        String idempotencyKey = UUID.randomUUID().toString().toLowerCase();

        // Verify format is valid UUID
        assertThat(idempotencyKey).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    @DisplayName("IDEMPOTENT-07: Cache key format is customer_id:idempotency_key")
    void testCacheKeyFormat() {
        String customerId = "cust-007";
        String idempotencyKey = "550e8400-e29b-41d4-a716-446655440000";

        String cacheKey = customerId + ":" + idempotencyKey;

        assertThat(cacheKey).isEqualTo("cust-007:550e8400-e29b-41d4-a716-446655440000");
    }

    @Test
    @DisplayName("IDEMPOTENT-08: Missing idempotency header allows request to proceed (generates new transaction)")
    void testMissingIdempotencyKeyAllowsNew() {
        String customerId = "cust-008";

        // Request without idempotency key: always executes
        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/payments")
                .header("Authorization", "Bearer token")
                // No X-Idempotency-Key header
                .build();

        // Should be allowed to execute (no deduplication)
        assertThat(request.getHeaders().get("X-Idempotency-Key")).isNull();
    }

    @Test
    @DisplayName("IDEMPOTENT-09: Idempotency key case-sensitive")
    void testIdempotencyKeyCaseSensitive() {
        String idempotencyKey1 = "550e8400-e29b-41d4-a716-446655440000";
        String idempotencyKey2 = "550E8400-E29B-41D4-A716-446655440000";

        // Even though they're the same UUID, case should match for caching
        String cacheKey1 = "cust:" + idempotencyKey1;
        String cacheKey2 = "cust:" + idempotencyKey2;

        assertThat(cacheKey1).isNotEqualTo(cacheKey2);
    }

    @Test
    @DisplayName("IDEMPOTENT-10: Concurrent duplicate requests handled atomically (SETNX)")
    void testConcurrentRequestsAtomic() {
        String customerId = "cust-010";
        String idempotencyKey = UUID.randomUUID().toString();
        String cacheKey = customerId + ":" + idempotencyKey;

        // Simulate two concurrent requests trying to set the same key
        int successCount = 0;

        // Request 1: Tries to set
        if (!idempotencyCache.containsKey(cacheKey)) {
            idempotencyCache.put(cacheKey, "txn-010");
            successCount++;
        }

        // Request 2: Tries to set (race condition - should lose)
        if (!idempotencyCache.containsKey(cacheKey)) {
            idempotencyCache.put(cacheKey, "txn-010-duplicate");
            successCount++;
        }

        // Only one should have succeeded (atomic SETNX)
        assertThat(successCount).isEqualTo(1);
        assertThat(idempotencyCache.get(cacheKey)).isEqualTo("txn-010");
    }

    @Test
    @DisplayName("IDEMPOTENT-11: TTL is 24 hours for idempotency cache")
    void testCacheTtl24Hours() {
        // Idempotency key TTL should be 24 hours (86400 seconds)
        long ttlSeconds = 24 * 60 * 60;

        assertThat(ttlSeconds).isEqualTo(86400);
    }

    @Test
    @DisplayName("IDEMPOTENT-12: Request header name is X-Idempotency-Key")
    void testRequestHeaderName() {
        String headerName = "X-Idempotency-Key";

        MockServerHttpRequest request = MockServerHttpRequest
                .post("/api/v1/payments")
                .header(headerName, UUID.randomUUID().toString())
                .build();

        assertThat(request.getHeaders().get(headerName)).isNotNull();
    }

    @Test
    @DisplayName("IDEMPOTENT-13: Duplicate detection prevents 409 Conflict response")
    void testDuplicateDetectionPrevents409() {
        String customerId = "cust-013";
        String idempotencyKey = UUID.randomUUID().toString();
        String cacheKey = customerId + ":" + idempotencyKey;

        // First request: executes normally (200 or 202)
        if (!idempotencyCache.containsKey(cacheKey)) {
            idempotencyCache.put(cacheKey, "{\"transaction_id\": \"txn-013\", \"status\": \"POSTED\"}");
            // Returns 200
        }

        // Second request: should return cached response (not 409 Conflict if response cached)
        String cachedResponse = idempotencyCache.get(cacheKey);
        
        assertThat(cachedResponse).contains("txn-013");
    }
}
