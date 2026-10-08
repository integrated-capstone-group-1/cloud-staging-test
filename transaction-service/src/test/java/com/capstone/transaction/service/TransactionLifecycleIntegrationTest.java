package com.capstone.transaction.service;

import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Integration Test: SEAM-ROH-03 (DIG-80, BR-30).
 * Verifies Compare-and-Set (CAS) Lifecycle Transition Seam:
 * - Transaction initiated in status AUTHORIZED
 * - Two concurrent worker threads race to claim status PROCESSING (SETTLING)
 * - Exactly 1 worker wins the CAS atomic update and proceeds to settlement
 * - The second worker receives 0 updated rows and safely aborts.
 */
class TransactionLifecycleIntegrationTest {

    @Test
    @DisplayName("SEAM-ROH-03: Two concurrent worker threads execute CAS claim; exactly 1 wins and 1 aborts")
    void verifyCasClaimTransition() throws Exception {
        String txnId = "TXN-CAS-1";

        // Simulated atomic CAS database repository
        AtomicBoolean casClaimed = new AtomicBoolean(false);
        TransactionMasterRepository repository = Mockito.mock(TransactionMasterRepository.class);

        when(repository.updateStatusCas(
                eq(txnId),
                eq("AUTHORIZED"),
                eq("PROCESSING"),
                eq("SETTLING"),
                any(LocalDateTime.class)
        )).thenAnswer(invocation -> {
            // Atomic CAS at DB engine level: UPDATE WHERE status = 'AUTHORIZED'
            if (casClaimed.compareAndSet(false, true)) {
                return 1; // Exactly 1 row updated
            } else {
                return 0; // 0 rows updated because status is no longer AUTHORIZED
            }
        });

        LifecycleService lifecycleService = new LifecycleService(repository, null, null);

        int workers = 2;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(workers);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger abortCount = new AtomicInteger(0);

        for (int i = 0; i < workers; i++) {
            executor.submit(() -> {
                try {
                    startGate.await(); // Both workers unleash simultaneously
                    boolean wonClaim = lifecycleService.claimForSettlement(txnId);
                    if (wonClaim) {
                        successCount.incrementAndGet();
                    } else {
                        abortCount.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        // Crucial invariant: Exactly 1 thread wins the CAS claim; 1 thread safely aborts
        assertThat(successCount.get())
                .as("Exactly 1 worker must win the CAS claim to execute settlement")
                .isEqualTo(1);
        assertThat(abortCount.get())
                .as("The competing worker must receive 0 updated rows and abort")
                .isEqualTo(1);
    }
}

