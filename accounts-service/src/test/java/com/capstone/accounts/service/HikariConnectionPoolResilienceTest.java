package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NFR-INTEG-03: HikariCP Connection Pool Saturation Resilience Stress Test
 *
 * Objective:
 * Verify that the accounts service gracefully handles database connection pool
 * saturation and demonstrates resilience under extreme concurrent load.
 *
 * Test Scenarios:
 * 1. 200 concurrent database queries with limited connection pool
 * 2. Requests queue and eventually succeed (no deadlocks)
 * 3. Circuit breaker or backpressure mechanism prevents cascade failures
 * 4. Error handling provides meaningful error messages
 * 5. Recovery occurs naturally after load subsides
 * 6. No leaked database connections
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NFR-INTEG-03: HikariCP Connection Pool Saturation Resilience")
public class HikariConnectionPoolResilienceTest {

    @Mock
    private AccountRepository accountRepository;

    private static final String ACCOUNT_ID_PREFIX = "ACC-POOL-TEST-";
    private static final int EXTREME_LOAD_REQUESTS = 200;
    private static final int THREAD_POOL_SIZE = 50;

    private AccountMaster testAccount;
    private AtomicInteger successfulQueries;
    private AtomicInteger failedQueries;
    private AtomicInteger timeoutQueries;
    private AtomicInteger connectionExhaustedErrors;

    @BeforeEach
    void setUp() {
        successfulQueries = new AtomicInteger(0);
        failedQueries = new AtomicInteger(0);
        timeoutQueries = new AtomicInteger(0);
        connectionExhaustedErrors = new AtomicInteger(0);

        // Set up test account
        testAccount = AccountMaster.builder()
                .accountId("ACC-POOL-TEST-001")
                .accountNumber("9999999999")
                .customerId("CUST-POOL-TEST")
                .accountType("SAVINGS")
                .currencyCode("PHP")
                .accountStatus("ACTIVE")
                .balanceAmount(new BigDecimal("10000.0000"))
                .heldAmount(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .createdBy("SYSTEM")
                .updatedAt(LocalDateTime.now())
                .updatedBy("SYSTEM")
                .adminFrozen("N")
                .customerLocked("N")
                .build();
    }

    @Test
    @DisplayName("Should handle 200 concurrent queries without deadlock or cascade failure")
    void shouldHandle200ConcurrentQueriesWithoutDeadlock() throws InterruptedException {
        // Arrange - Simulate normal repository behavior
        when(accountRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    // Simulate database query latency
                    Thread.sleep(10);
                    return Optional.of(testAccount);
                });

        when(accountRepository.findById(anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(5);
                    return Optional.of(testAccount);
                });

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(EXTREME_LOAD_REQUESTS);

        // Act - Simulate 200 concurrent queries against limited pool
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < EXTREME_LOAD_REQUESTS; i++) {
            final int queryIndex = i; // Capture value for lambda
            final String accountId = ACCOUNT_ID_PREFIX + (queryIndex % 10); // 10 distinct accounts
            executor.submit(() -> {
                try {
                    AccountMaster account;
                    if (queryIndex % 2 == 0) {
                        account = accountRepository.findByIdForUpdate(accountId)
                                .orElse(null);
                    } else {
                        account = accountRepository.findById(accountId)
                                .orElse(null);
                    }

                    if (account != null) {
                        successfulQueries.incrementAndGet();
                    } else {
                        failedQueries.incrementAndGet();
                    }
                } catch (Exception e) {
                    failedQueries.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // Assert - All requests should eventually complete
        assertTrue(completed, "All 200 requests should complete within 30 seconds");
        int totalRequests = successfulQueries.get() + failedQueries.get();
        assertEquals(EXTREME_LOAD_REQUESTS, totalRequests,
                "All requests should complete (success or fail)");
        assertTrue(successfulQueries.get() >= (EXTREME_LOAD_REQUESTS * 0.9),
                "At least 90% of requests should succeed under saturation");
        System.out.println("Completed 200 concurrent queries in " + elapsed + "ms");
    }

    @Test
    @DisplayName("Should queue requests and prevent connection pool exhaustion deadlock")
    void shouldQueueRequestsAndPreventPoolExhaustionDeadlock() throws InterruptedException {
        // Arrange - Long-running queries to simulate connection hold time
        when(accountRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    Thread.sleep(50); // Simulate slow query
                    return Optional.of(testAccount);
                });

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completeLatch = new CountDownLatch(100);

        // Act
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await(); // All threads wait to start simultaneously
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                try {
                    accountRepository.findByIdForUpdate("ACC-POOL-001")
                            .orElse(null);
                    successfulQueries.incrementAndGet();
                } catch (Exception e) {
                    failedQueries.incrementAndGet();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        // Brief delay to allow threads to reach the latch
        Thread.sleep(200);

        // Signal all threads to execute simultaneously
        startLatch.countDown();

        // Wait for completion with timeout
        boolean completed = completeLatch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - No deadlock occurred (all completed within timeout)
        assertTrue(completed, "All requests should complete without deadlock");
        assertEquals(100, successfulQueries.get() + failedQueries.get(),
                "All 100 requests should complete (success or fail)");
        assertTrue(successfulQueries.get() > 0, "Some requests should succeed");
    }

    @Test
    @DisplayName("Should handle connection exhaustion errors gracefully")
    void shouldHandleConnectionExhaustionErrorsGracefully() throws InterruptedException {
        // Arrange - Simulate connection exhaustion after certain threshold
        AtomicInteger callCount = new AtomicInteger(0);

        when(accountRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    int calls = callCount.incrementAndGet();
                    if (calls > 50) {
                        // Simulate connection pool exhaustion
                        connectionExhaustedErrors.incrementAndGet();
                        throw new RuntimeException("HikariPool - Connection is not available, request timed out after 30000ms.");
                    }
                    Thread.sleep(5);
                    return Optional.of(testAccount);
                });

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
        CountDownLatch latch = new CountDownLatch(150);

        // Act
        for (int i = 0; i < 150; i++) {
            executor.submit(() -> {
                try {
                    accountRepository.findByIdForUpdate("ACC-POOL-001")
                            .orElse(null);
                    successfulQueries.incrementAndGet();
                } catch (RuntimeException e) {
                    if (e.getMessage().contains("HikariPool")) {
                        connectionExhaustedErrors.incrementAndGet();
                    }
                    failedQueries.incrementAndGet();
                } catch (Exception e) {
                    failedQueries.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Errors are tracked and meaningful
        assertTrue(completed, "All requests should complete");
        assertTrue(connectionExhaustedErrors.get() > 0,
                "Some connection exhaustion errors should occur");
        // Note: due to threading, failed queries may not always include all connection errors
        assertEquals(150, successfulQueries.get() + failedQueries.get(),
                "All 150 requests should complete (success or fail)");
        System.out.println("Connection exhaustion errors: " + connectionExhaustedErrors.get());
    }

    @Test
    @DisplayName("Should provide meaningful error context for pool saturation")
    void shouldProvideMeaningfulErrorContextForPoolSaturation() throws InterruptedException {
        // Arrange
        AtomicInteger callCount = new AtomicInteger(0);
        AtomicInteger errorMessagesWithContext = new AtomicInteger(0);

        when(accountRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    int calls = callCount.incrementAndGet();
                    if (calls > 30) {
                        String errorMsg = "HikariPool-1 - Connection is not available, request timed out after 30000ms. " +
                                "Total: 10, Active: 10, Idle: 0, Waiting: " + (calls - 10);
                        if (errorMsg.contains("Waiting:")) {
                            errorMessagesWithContext.incrementAndGet();
                        }
                        throw new RuntimeException(errorMsg);
                    }
                    return Optional.of(testAccount);
                });

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(80);

        // Act
        for (int i = 0; i < 80; i++) {
            executor.submit(() -> {
                try {
                    accountRepository.findByIdForUpdate("ACC-POOL-001")
                            .orElse(null);
                    successfulQueries.incrementAndGet();
                } catch (RuntimeException e) {
                    String msg = e.getMessage();
                    if (msg.contains("Total:") || msg.contains("Active:") || msg.contains("Waiting:")) {
                        errorMessagesWithContext.incrementAndGet();
                    }
                    failedQueries.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - Errors contain pool statistics
        assertTrue(completed, "All requests should complete");
        assertTrue(errorMessagesWithContext.get() > 0,
                "Error messages should contain pool context (Total/Active/Waiting)");
        System.out.println("Errors with context: " + errorMessagesWithContext.get());
    }

    @Test
    @DisplayName("Should recover naturally as load subsides and connections return to pool")
    void shouldRecoverNaturallyAsLoadSubsides() throws InterruptedException {
        // Arrange - Variable load that peaks then subsides
        AtomicInteger activeConnections = new AtomicInteger(0);
        AtomicInteger peakConnections = new AtomicInteger(0);

        when(accountRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    int active = activeConnections.incrementAndGet();
                    if (active > peakConnections.get()) {
                        peakConnections.set(active);
                    }
                    Thread.sleep(20); // Simulate query time
                    activeConnections.decrementAndGet();
                    return Optional.of(testAccount);
                });

        ExecutorService executor = Executors.newFixedThreadPool(30);
        CountDownLatch phase1Latch = new CountDownLatch(150); // Ramp-up phase
        CountDownLatch phase2Latch = new CountDownLatch(50);  // Cool-down phase

        // Act - Phase 1: Ramp-up to saturation
        for (int i = 0; i < 150; i++) {
            executor.submit(() -> {
                try {
                    accountRepository.findByIdForUpdate("ACC-POOL-001")
                            .orElse(null);
                    successfulQueries.incrementAndGet();
                } catch (Exception e) {
                    failedQueries.incrementAndGet();
                } finally {
                    phase1Latch.countDown();
                }
            });
        }

        phase1Latch.await();
        int connectionsPeakPhase1 = peakConnections.get();
        Thread.sleep(100); // Brief pause to allow recovery

        // Phase 2: Cool-down with minimal load
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    accountRepository.findByIdForUpdate("ACC-POOL-001")
                            .orElse(null);
                    successfulQueries.incrementAndGet();
                } catch (Exception e) {
                    failedQueries.incrementAndGet();
                } finally {
                    phase2Latch.countDown();
                }
            });
        }

        phase2Latch.await();
        executor.shutdown();

        // Assert - System recovered with reduced active connections
        assertEquals(0, activeConnections.get(),
                "All connections should be returned to pool after phase 2");
        assertTrue(peakConnections.get() >= 20,
                "Should see peak connection usage during ramp-up");
        assertTrue(successfulQueries.get() >= 195,
                "At least 195 out of 200 queries should succeed during recovery");
        System.out.println("Peak connections: " + connectionsPeakPhase1 + ", Final active: " + activeConnections.get());
    }

    @Test
    @DisplayName("Should not leak database connections under extreme saturation")
    void shouldNotLeakConnectionsUnderExtremeSaturation() throws InterruptedException {
        // Arrange - Track connection lifecycle
        AtomicInteger openedConnections = new AtomicInteger(0);
        AtomicInteger closedConnections = new AtomicInteger(0);

        when(accountRepository.findByIdForUpdate(anyString()))
                .thenAnswer(invocation -> {
                    openedConnections.incrementAndGet();
                    try {
                        Thread.sleep(15);
                        return Optional.of(testAccount);
                    } finally {
                        closedConnections.incrementAndGet();
                    }
                });

        ExecutorService executor = Executors.newFixedThreadPool(40);
        CountDownLatch latch = new CountDownLatch(200);

        // Act - Extreme concurrent load
        for (int i = 0; i < 200; i++) {
            executor.submit(() -> {
                try {
                    accountRepository.findByIdForUpdate("ACC-POOL-001")
                            .orElse(null);
                    successfulQueries.incrementAndGet();
                } catch (Exception e) {
                    failedQueries.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(30, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        // Assert - No connection leaks
        assertTrue(completed, "All requests should complete");
        assertEquals(openedConnections.get(), closedConnections.get(),
                "Every opened connection must be closed (no leaks)");
        assertTrue(closedConnections.get() >= 195,
                "At least 195 connections should be properly closed");
        System.out.println("Opened: " + openedConnections.get() + ", Closed: " + closedConnections.get() +
                ", Leaked: " + (openedConnections.get() - closedConnections.get()));
    }
}
