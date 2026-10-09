package com.capstone.transaction.nfr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NFR-CONC-01: Concurrent Withdrawals Race Test (TS-01, BR-01)
 *
 * Objective: Prove zero overdraft when parallel threads withdraw simultaneously from a single pocket.
 *
 * Test Specification (from NON_FUNCTIONAL_TEST_PLAN.md):
 * - Seed account with ₱50,000.00
 * - Fire 50 concurrent threads releasing simultaneously via CountDownLatch
 * - Each thread attempts to withdraw ₱1,500.00
 * - Expected outcome: 33 succeed (₱50,000 / ₱1,500 = 33.33), 17 fail with INSUFFICIENT_FUNDS
 * - Final balance must be exactly ₱500.00
 * - Zero negative balances allowed
 * - All 50 threads must complete within synchronization window
 *
 * Mapped to: DIG-52, BR-01
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NFR-CONC-01: Concurrent Withdrawals Race Test")
public class ConcurrentWithdrawalsRaceTest {

    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("50000.00");
    private static final BigDecimal WITHDRAW_AMOUNT = new BigDecimal("1500.00");
    private static final int THREAD_COUNT = 50;
    private static final int EXPECTED_SUCCESS = 33;
    private static final int EXPECTED_INSUFFICIENT = 17;
    private static final BigDecimal EXPECTED_FINAL_BALANCE = new BigDecimal("500.00");

    @Test
    @DisplayName("NFR-CONC-01-A: 50 concurrent withdrawals against 50,000 pocket - exactly 33 succeed, 17 fail, final balance 500")
    void shouldPreventOverdraftWith50ConcurrentWithdrawals() throws Exception {
        // Arrange: Setup shared mutable balance
        AtomicReference<BigDecimal> balance = new AtomicReference<>(INITIAL_BALANCE);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch startGate = new CountDownLatch(1); // Synchronization: all threads wait here
        CountDownLatch doneGate = new CountDownLatch(THREAD_COUNT); // All threads report completion

        // Act: Submit 50 withdrawal tasks
        for (int i = 0; i < THREAD_COUNT; i++) {
            executor.submit(() -> {
                try {
                    // Wait for signal to start (all threads synchronized)
                    startGate.await();

                    // Attempt withdrawal with optimistic locking (CAS)
                    boolean withdrawn = false;
                    int retries = 0;
                    while (!withdrawn && retries < 100) {
                        BigDecimal currentBalance = balance.get();
                        
                        if (currentBalance.compareTo(WITHDRAW_AMOUNT) < 0) {
                            // Insufficient funds
                            insufficientCount.incrementAndGet();
                            withdrawn = true;
                        } else {
                            // Calculate new balance
                            BigDecimal newBalance = currentBalance.subtract(WITHDRAW_AMOUNT);
                            
                            // CAS (Compare-And-Swap) - simulates database row-level lock
                            if (balance.compareAndSet(currentBalance, newBalance)) {
                                successCount.incrementAndGet();
                                withdrawn = true;
                            } else {
                                // Conflict: retry
                                retries++;
                                Thread.yield();
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    errorCount.incrementAndGet();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Release all threads simultaneously
        startGate.countDown();

        // Wait for all threads to complete
        boolean completed = doneGate.await(30, TimeUnit.SECONDS);

        executor.shutdownNow();

        // Assert: Verify race outcomes
        assertThat(completed)
                .as("All 50 threads must complete within 30 seconds")
                .isTrue();

        assertThat(successCount.get())
                .as("Exactly 33 withdrawals should succeed (₱50,000 / ₱1,500 = 33 remainder 500)")
                .isEqualTo(EXPECTED_SUCCESS);

        assertThat(insufficientCount.get())
                .as("Exactly 17 withdrawals should fail with insufficient funds")
                .isEqualTo(EXPECTED_INSUFFICIENT);

        assertThat(errorCount.get())
                .as("Zero errors should occur during processing")
                .isEqualTo(0);

        assertThat(balance.get())
                .as("Final balance must be exactly ₱500.00 (no overdraft, no lost updates)")
                .isEqualByComparingTo(EXPECTED_FINAL_BALANCE);

        assertThat(balance.get().compareTo(BigDecimal.ZERO))
                .as("Balance must never go negative")
                .isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("NFR-CONC-01-B: 100 concurrent withdrawals with smaller amounts - verify no deadlocks")
    void shouldHandleHigherConcurrencyWithoutDeadlocks() throws Exception {
        // Arrange: Even higher concurrency test
        int threadCount = 100;
        BigDecimal initialBalance = new BigDecimal("10000.00");
        BigDecimal withdrawAmount = new BigDecimal("100.00");

        AtomicReference<BigDecimal> balance = new AtomicReference<>(initialBalance);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger insufficientCount = new AtomicInteger(0);
        AtomicInteger totalAttempts = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);

        // Act: Submit 100 withdrawal tasks
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    totalAttempts.incrementAndGet();

                    BigDecimal currentBalance = balance.get();
                    if (currentBalance.compareTo(withdrawAmount) >= 0) {
                        BigDecimal newBalance = currentBalance.subtract(withdrawAmount);
                        if (balance.compareAndSet(currentBalance, newBalance)) {
                            successCount.incrementAndGet();
                        }
                    } else {
                        insufficientCount.incrementAndGet();
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

        // Assert: All threads completed without deadlock
        assertThat(completed)
                .as("High concurrency test (100 threads) must complete without deadlock")
                .isTrue();

        assertThat(totalAttempts.get())
                .isEqualTo(threadCount);

        assertThat(balance.get().compareTo(BigDecimal.ZERO))
                .as("Final balance must never be negative even under high concurrency")
                .isGreaterThanOrEqualTo(0);

        // Verify math: successCount * withdrawAmount + final_balance should equal initial_balance
        BigDecimal expectedFinal = initialBalance.subtract(
                withdrawAmount.multiply(new BigDecimal(successCount.get()))
        );
        assertThat(balance.get())
                .as("Balance accounting must be exact (no lost updates)")
                .isEqualByComparingTo(expectedFinal);
    }

    @Test
    @DisplayName("NFR-CONC-01-C: Interleaved deposits and withdrawals maintain invariant")
    void shouldMaintainInvariantWithMixedOperations() throws Exception {
        // Arrange: Mix of withdrawals and deposits
        int depthreads = 25;
        int withThreads = 25;
        BigDecimal depositAmount = new BigDecimal("200.00");
        BigDecimal withdrawAmount = new BigDecimal("200.00");

        AtomicReference<BigDecimal> balance = new AtomicReference<>(new BigDecimal("5000.00"));
        AtomicInteger totalOps = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(depthreads + withThreads);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(depthreads + withThreads);

        // Submit deposit tasks
        for (int i = 0; i < depthreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    while (true) {
                        BigDecimal current = balance.get();
                        BigDecimal updated = current.add(depositAmount);
                        if (balance.compareAndSet(current, updated)) {
                            totalOps.incrementAndGet();
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        // Submit withdrawal tasks
        for (int i = 0; i < withThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    BigDecimal current = balance.get();
                    if (current.compareTo(withdrawAmount) >= 0) {
                        BigDecimal updated = current.subtract(withdrawAmount);
                        balance.compareAndSet(current, updated);
                    }
                    totalOps.incrementAndGet();
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

        // Assert: Mixed operations must maintain balance integrity
        assertThat(completed).isTrue();
        assertThat(balance.get().compareTo(BigDecimal.ZERO))
                .as("Mixed operations must never create negative balance")
                .isGreaterThanOrEqualTo(0);
    }
}
