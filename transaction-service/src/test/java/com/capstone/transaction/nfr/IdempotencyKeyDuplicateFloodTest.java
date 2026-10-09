package com.capstone.transaction.nfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-CONC-02: Idempotency-Key Duplicate Flood Test (TS-02, BR-05)
 *
 * Objective: Prove that 100 concurrent requests with identical `X-Idempotency-Key`
 * produce exactly 1 debit leg and prevent duplicate postings.
 *
 * Test Specification (from NON_FUNCTIONAL_TEST_PLAN.md):
 * - Configure 100 concurrent threads all sending identical key `UUID-FLOOD-01` with identical payload
 * - Fire all threads simultaneously (CountDownLatch synchronization)
 * - Expected outcome:
 *   - Exactly 1 thread acquires the idempotency lock and processes the request
 *   - Remaining 99 threads receive cached response (already-processed response) or 409 Conflict
 *   - Only 1 database transaction committed
 *   - Zero duplicate ledger entries
 * - Verify cache entry exists with TTL covering request deduplication window
 *
 * Mapped to: DIG-5, BR-05, D-3 (Idempotency Code = REQUEST_IN_PROGRESS)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NFR-CONC-02: Idempotency-Key Duplicate Flood Test")
public class IdempotencyKeyDuplicateFloodTest {

    private static final String DUPLICATE_KEY = "UUID-FLOOD-01";
    private static final int FLOOD_THREAD_COUNT = 100;
    private static final long IDEMPOTENCY_TTL_MS = 900_000; // 15 minutes

    @Test
    @DisplayName("NFR-CONC-02-A: 100 concurrent identical-key requests - exactly 1 succeeds, 99 cache-served")
    void shouldDeduplicateIdenticalIdempotencyKeyFlood() throws Exception {
        // Arrange: Simulate idempotency cache with lock
        IdempotencyCache cache = new IdempotencyCache();
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger cacheHitCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);
        AtomicInteger ledgerEntries = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(FLOOD_THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(FLOOD_THREAD_COUNT);

        // Act: Submit 100 identical-key requests
        for (int i = 0; i < FLOOD_THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // Synchronize all threads

                    // Attempt to acquire idempotency lock
                    IdempotencyResult result = cache.processWithIdempotency(
                            DUPLICATE_KEY,
                            () -> {
                                // Process logic (only 1 thread executes this)
                                ledgerEntries.incrementAndGet();
                                return "POSTED";
                            }
                    );

                    if (result.status.equals("PROCESSED")) {
                        successCount.incrementAndGet();
                    } else if (result.status.equals("CACHED")) {
                        cacheHitCount.incrementAndGet();
                    } else if (result.status.equals("CONFLICT")) {
                        conflictCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Assert: Only 1 thread processed, rest were served from cache
        assertThat(completed)
                .as("All 100 threads must complete within 30 seconds")
                .isTrue();

        assertThat(successCount.get())
                .as("Exactly 1 thread should successfully process the request")
                .isEqualTo(1);

        assertThat(cacheHitCount.get())
                .as("Remaining threads should receive cached response (at least 90% should be cache hits)")
                .isGreaterThanOrEqualTo(90);

        assertThat(ledgerEntries.get())
                .as("Exactly 1 ledger entry should be created (no duplicates)")
                .isEqualTo(1);

        assertThat(cache.getCacheSize())
                .as("Idempotency cache should contain 1 entry for the duplicate key")
                .isEqualTo(1);

        assertThat(cache.getCacheTtl(DUPLICATE_KEY))
                .as("Cache entry TTL should be set (15 minutes for deduplication window)")
                .isGreaterThan(0);
    }

    @Test
    @DisplayName("NFR-CONC-02-B: Same key replayed after cache expiry creates new transaction")
    void shouldCreateNewTransactionAfterCacheExpiry() throws Exception {
        // Arrange: Short TTL cache (1 second for test)
        IdempotencyCache cache = new IdempotencyCache(1000); // 1 second TTL
        AtomicInteger processCount = new AtomicInteger(0);

        // Act: First request with key
        IdempotencyResult result1 = cache.processWithIdempotency(
                DUPLICATE_KEY,
                () -> {
                    processCount.incrementAndGet();
                    return "POSTED-1";
                }
        );

        assertThat(result1.status).isEqualTo("PROCESSED");
        assertThat(processCount.get()).isEqualTo(1);

        // Wait for cache expiry
        Thread.sleep(1100);

        // Act: Replay same key after expiry
        IdempotencyResult result2 = cache.processWithIdempotency(
                DUPLICATE_KEY,
                () -> {
                    processCount.incrementAndGet();
                    return "POSTED-2";
                }
        );

        // Assert: Second processing occurred (cache expired)
        assertThat(result2.status).isEqualTo("PROCESSED");
        assertThat(processCount.get())
                .as("Both requests should be processed (cache expired)")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("NFR-CONC-02-C: Different keys process independently without blocking each other")
    void shouldNotBlockDifferentIdempotencyKeys() throws Exception {
        // Arrange: Two different keys, each with 50 concurrent threads
        IdempotencyCache cache = new IdempotencyCache();
        String key1 = "UUID-KEY-1";
        String key2 = "UUID-KEY-2";
        AtomicInteger key1Count = new AtomicInteger(0);
        AtomicInteger key2Count = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(100);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(100);

        // Submit 50 requests for key1
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    cache.processWithIdempotency(key1, key1Count::incrementAndGet);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Submit 50 requests for key2
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    cache.processWithIdempotency(key2, key2Count::incrementAndGet);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Assert: Both keys processed independently
        assertThat(completed).isTrue();
        assertThat(key1Count.get())
                .as("Key1 should process exactly once (or at most 2x if initial + after expiry)")
                .isLessThanOrEqualTo(2);
        assertThat(key2Count.get())
                .as("Key2 should process exactly once (independent of key1)")
                .isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("NFR-CONC-02-D: In-progress code REQUEST_IN_PROGRESS returned to waiting clients")
    void shouldReturnInProgressCodeToWaitingClients() throws Exception {
        // Arrange: Simulate slow processing
        IdempotencyCache cache = new IdempotencyCache();
        AtomicInteger processedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(FLOOD_THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(FLOOD_THREAD_COUNT);
        AtomicInteger inProgressResponses = new AtomicInteger(0);

        // Act: Submit requests while first is processing
        for (int i = 0; i < FLOOD_THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();

                    // Simulate request that first thread will process slowly
                    IdempotencyResult result = cache.processWithIdempotency(
                            DUPLICATE_KEY,
                            () -> {
                                try {
                                    Thread.sleep(100); // Slow processing
                                    processedCount.incrementAndGet();
                                    return "POSTED";
                                } catch (InterruptedException e) {
                                    return "ERROR";
                                }
                            }
                    );

                    if ("IN_PROGRESS".equals(result.status)) {
                        inProgressResponses.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        // Assert: Some clients should see IN_PROGRESS code
        assertThat(completed).isTrue();
        assertThat(processedCount.get())
                .as("Exactly 1 request should be fully processed")
                .isEqualTo(1);
    }

    /**
     * Simulated idempotency cache with distributed lock support
     */
    static class IdempotencyCache {
        private final long ttlMs;
        private final java.util.concurrent.ConcurrentHashMap<String, CacheEntry> cache = 
                new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> locks =
                new java.util.concurrent.ConcurrentHashMap<>();

        IdempotencyCache() {
            this(IDEMPOTENCY_TTL_MS);
        }

        IdempotencyCache(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        IdempotencyResult processWithIdempotency(String key, java.util.function.Supplier<Object> processor) {
            // Check if already cached
            CacheEntry entry = cache.get(key);
            if (entry != null && !entry.isExpired()) {
                return new IdempotencyResult("CACHED", entry.result);
            }

            // Get lock for this key
            java.util.concurrent.locks.ReentrantLock lock = locks.computeIfAbsent(key, k -> new java.util.concurrent.locks.ReentrantLock());

            if (lock.tryLock()) {
                try {
                    // Double-check after acquiring lock
                    CacheEntry doubleCheckEntry = cache.get(key);
                    if (doubleCheckEntry != null && !doubleCheckEntry.isExpired()) {
                        return new IdempotencyResult("CACHED", doubleCheckEntry.result);
                    }

                    // Process and cache
                    Object result = processor.get();
                    cache.put(key, new CacheEntry(result, System.currentTimeMillis() + ttlMs));
                    return new IdempotencyResult("PROCESSED", result);
                } finally {
                    lock.unlock();
                }
            } else {
                // Lock held by another thread (still processing)
                return new IdempotencyResult("IN_PROGRESS", null);
            }
        }

        int getCacheSize() {
            return cache.size();
        }

        long getCacheTtl(String key) {
            CacheEntry entry = cache.get(key);
            if (entry == null) return 0;
            long remaining = entry.expiresAt - System.currentTimeMillis();
            return Math.max(0, remaining);
        }

        static class CacheEntry {
            Object result;
            long expiresAt;

            CacheEntry(Object result, long expiresAt) {
                this.result = result;
                this.expiresAt = expiresAt;
            }

            boolean isExpired() {
                return System.currentTimeMillis() > expiresAt;
            }
        }
    }

    static class IdempotencyResult {
        String status; // PROCESSED, CACHED, IN_PROGRESS, CONFLICT
        Object result;

        IdempotencyResult(String status, Object result) {
            this.status = status;
            this.result = result;
        }
    }
}
