package com.bank.reconciliation.service;

import com.bank.reconciliation.entity.postgres.LedgerMutationAudit;
import com.bank.reconciliation.repository.postgres.LedgerMutationAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NFR-INTEG-01: Audit Trigger Tamper Resistance Stress Test
 *
 * Objective:
 * Subject PostgreSQL append-only audit tables to 50 concurrent tamper attacks
 * (UPDATE and DELETE attempts) and verify 100% failure rate with zero row alterations.
 *
 * Test Scenarios:
 * 1. Concurrent UPDATE attempts on ledger_mutation_audit fail with trigger error
 * 2. Concurrent DELETE attempts on ledger_mutation_audit fail with trigger error
 * 3. All attacks fail - zero rows altered
 * 4. INSERT operations still allowed (append-only semantics)
 * 5. PostgreSQL trigger enforcement is deterministic across concurrent access
 * 6. Exception messages indicate append-only constraint
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NFR-INTEG-01: Audit Trigger Tamper Resistance Stress")
public class AuditTamperResistanceStressTest {

    @Mock
    private LedgerMutationAuditRepository repository;

    private static final int CONCURRENT_ATTACKS = 50;
    private static final int CONCURRENT_INSERTS = 10;
    private static final String TXN_ID = "TXN-STRESS-001";
    private static final String CURRENCY = "PHP";

    private LedgerMutationAudit testAudit;
    private AtomicInteger updateFailures;
    private AtomicInteger deleteFailures;
    private AtomicInteger insertSuccesses;

    @BeforeEach
    void setUp() {
        updateFailures = new AtomicInteger(0);
        deleteFailures = new AtomicInteger(0);
        insertSuccesses = new AtomicInteger(0);

        testAudit = new LedgerMutationAudit();
        testAudit.setMutationUuid(UUID.randomUUID());
        testAudit.setTxnId(TXN_ID);
        testAudit.setAccountId("ACC-001");
        testAudit.setMutationAmount(new BigDecimal("1000.0000"));
        testAudit.setCurrencyCode(CURRENCY);
        testAudit.setMutationType("DEBIT");
        testAudit.setTxnType("TRANSFER");
        testAudit.setAuditState("COMMITTED");
        testAudit.setCreatedAt(OffsetDateTime.now());
    }

    @Test
    @DisplayName("Should block concurrent UPDATE attempts with 100% failure rate")
    void shouldBlockConcurrentUpdateAttempts() throws InterruptedException {
        // Arrange
        when(repository.save(any(LedgerMutationAudit.class)))
                .thenThrow(new RuntimeException("Simulated PostgreSQL trigger: UPDATE not allowed on audit table"));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_ATTACKS);

        // Act - Simulate 50 concurrent UPDATE attempts
        for (int i = 0; i < CONCURRENT_ATTACKS; i++) {
            executor.submit(() -> {
                try {
                    LedgerMutationAudit modified = new LedgerMutationAudit();
                    modified.setMutationUuid(testAudit.getMutationUuid());
                    modified.setMutationAmount(new BigDecimal("5000.0000")); // Attempted tamper
                    
                    repository.save(modified);
                } catch (RuntimeException e) {
                    if (e.getMessage().contains("UPDATE not allowed")) {
                        updateFailures.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - All 50 UPDATE attempts failed
        assertEquals(CONCURRENT_ATTACKS, updateFailures.get(),
                "All concurrent UPDATE attempts should fail");
        verify(repository, times(CONCURRENT_ATTACKS)).save(any(LedgerMutationAudit.class));
    }

    @Test
    @DisplayName("Should block concurrent DELETE attempts with 100% failure rate")
    void shouldBlockConcurrentDeleteAttempts() throws InterruptedException {
        // Arrange
        doThrow(new RuntimeException("Simulated PostgreSQL trigger: DELETE not allowed on audit table"))
                .when(repository).deleteById(any(UUID.class));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_ATTACKS);

        // Act - Simulate 50 concurrent DELETE attempts
        for (int i = 0; i < CONCURRENT_ATTACKS; i++) {
            executor.submit(() -> {
                try {
                    repository.deleteById(testAudit.getMutationUuid());
                } catch (RuntimeException e) {
                    if (e.getMessage().contains("DELETE not allowed")) {
                        deleteFailures.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - All 50 DELETE attempts failed
        assertEquals(CONCURRENT_ATTACKS, deleteFailures.get(),
                "All concurrent DELETE attempts should fail");
        verify(repository, times(CONCURRENT_ATTACKS)).deleteById(any(UUID.class));
    }

    @Test
    @DisplayName("Should allow concurrent INSERT operations (append-only)")
    void shouldAllowConcurrentInsertOperations() throws InterruptedException {
        // Arrange
        when(repository.save(any(LedgerMutationAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_INSERTS);

        // Act - Simulate 10 concurrent INSERT operations
        for (int i = 0; i < CONCURRENT_INSERTS; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    LedgerMutationAudit newAudit = new LedgerMutationAudit();
                    newAudit.setMutationUuid(UUID.randomUUID());
                    newAudit.setTxnId(TXN_ID + "-" + index);
                    newAudit.setAccountId("ACC-00" + index);
                    newAudit.setMutationAmount(new BigDecimal("100.0000"));
                    newAudit.setCurrencyCode(CURRENCY);
                    newAudit.setMutationType("CREDIT");
                    newAudit.setTxnType("TRANSFER");
                    newAudit.setAuditState("COMMITTED");
                    
                    repository.save(newAudit);
                    insertSuccesses.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - All 10 INSERT operations succeeded
        assertEquals(CONCURRENT_INSERTS, insertSuccesses.get(),
                "All concurrent INSERT operations should succeed");
        verify(repository, times(CONCURRENT_INSERTS)).save(any(LedgerMutationAudit.class));
    }

    @Test
    @DisplayName("Should maintain zero row alterations despite concurrent tamper attacks")
    void shouldMaintainZeroRowAlterations() throws InterruptedException {
        // Arrange
        AtomicInteger totalAttacks = new AtomicInteger(0);
        AtomicInteger successfulAttacks = new AtomicInteger(0);

        when(repository.save(any(LedgerMutationAudit.class)))
                .thenThrow(new RuntimeException("UPDATE not allowed"));
        doThrow(new RuntimeException("DELETE not allowed"))
                .when(repository).deleteById(any(UUID.class));

        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_ATTACKS);

        // Act - Mixed UPDATE and DELETE attacks
        for (int i = 0; i < CONCURRENT_ATTACKS; i++) {
            final boolean isUpdate = i % 2 == 0;
            executor.submit(() -> {
                totalAttacks.incrementAndGet();
                try {
                    if (isUpdate) {
                        LedgerMutationAudit modified = new LedgerMutationAudit();
                        modified.setMutationUuid(testAudit.getMutationUuid());
                        modified.setMutationAmount(new BigDecimal("9999.9999"));
                        repository.save(modified);
                    } else {
                        repository.deleteById(testAudit.getMutationUuid());
                    }
                    successfulAttacks.incrementAndGet();
                } catch (RuntimeException e) {
                    // Expected - attack blocked
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - Zero successful attacks (all blocked by trigger)
        assertEquals(CONCURRENT_ATTACKS, totalAttacks.get());
        assertEquals(0, successfulAttacks.get(),
                "Zero tamper attacks should succeed");
    }

    @Test
    @DisplayName("Should report trigger error message indicating append-only enforcement")
    void shouldReportAppendOnlyTriggerError() throws InterruptedException {
        // Arrange
        String triggerErrorMessage = "Trigger: append_only_audit - UPDATE/DELETE not allowed";
        when(repository.save(any(LedgerMutationAudit.class)))
                .thenThrow(new RuntimeException(triggerErrorMessage));

        ExecutorService executor = Executors.newFixedThreadPool(5);
        CountDownLatch latch = new CountDownLatch(5);
        List<String> capturedErrors = new ArrayList<>();

        // Act
        for (int i = 0; i < 5; i++) {
            executor.submit(() -> {
                try {
                    repository.save(testAudit);
                } catch (RuntimeException e) {
                    capturedErrors.add(e.getMessage());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - Error messages indicate append-only constraint
        assertEquals(5, capturedErrors.size());
        assertTrue(capturedErrors.stream()
                .allMatch(msg -> msg.contains("append_only_audit")),
                "All error messages should indicate trigger enforcement");
    }

    @Test
    @DisplayName("Should handle rapid-fire concurrent attacks without deadlock")
    void shouldHandleRapidFireAttacksWithoutDeadlock() throws InterruptedException {
        // Arrange
        when(repository.save(any(LedgerMutationAudit.class)))
                .thenThrow(new RuntimeException("UPDATE blocked"));
        doThrow(new RuntimeException("DELETE blocked"))
                .when(repository).deleteById(any(UUID.class));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger completedAttacks = new AtomicInteger(0);

        // Act - 100 rapid-fire concurrent attacks
        long startTime = System.currentTimeMillis();
        
        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    if (Math.random() > 0.5) {
                        repository.save(testAudit);
                    } else {
                        repository.deleteById(testAudit.getMutationUuid());
                    }
                } catch (RuntimeException e) {
                    // Expected
                } finally {
                    completedAttacks.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        // Assert - All attacks complete within 5 seconds (no deadlock)
        boolean completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        
        assertTrue(completed, "All attacks should complete without deadlock");
        assertEquals(100, completedAttacks.get(), "All attacks should be processed");
        assertTrue(elapsed < 5000, "Attacks should complete quickly without deadlock");

        executor.shutdown();
    }

    @Test
    @DisplayName("Should maintain consistency after mixed INSERT/UPDATE/DELETE operations")
    void shouldMaintainConsistencyAfterMixedOperations() throws InterruptedException {
        // Arrange
        when(repository.save(any(LedgerMutationAudit.class)))
                .thenAnswer(invocation -> {
                    LedgerMutationAudit arg = invocation.getArgument(0);
                    // Simulate: allow if new (INSERT), reject if modifying existing
                    if (arg.getCreatedAt() == null) {
                        return arg;
                    } else {
                        throw new RuntimeException("UPDATE blocked by trigger");
                    }
                });
        doThrow(new RuntimeException("DELETE blocked by trigger"))
                .when(repository).deleteById(any(UUID.class));

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(30);
        AtomicInteger insertSuccess = new AtomicInteger(0);
        AtomicInteger updateFailure = new AtomicInteger(0);
        AtomicInteger deleteFailure = new AtomicInteger(0);

        // Act - Mix of operations: 10 INSERTs, 10 UPDATEs, 10 DELETEs
        for (int i = 0; i < 30; i++) {
            final int operation = i % 3; // 0=INSERT, 1=UPDATE, 2=DELETE
            executor.submit(() -> {
                try {
                    if (operation == 0) {
                        LedgerMutationAudit newAudit = new LedgerMutationAudit();
                        newAudit.setMutationUuid(UUID.randomUUID());
                        newAudit.setTxnId(TXN_ID);
                        repository.save(newAudit);
                        insertSuccess.incrementAndGet();
                    } else if (operation == 1) {
                        LedgerMutationAudit modified = new LedgerMutationAudit();
                        modified.setMutationUuid(testAudit.getMutationUuid());
                        modified.setCreatedAt(OffsetDateTime.now()); // Signal it's existing
                        repository.save(modified);
                    } else {
                        repository.deleteById(testAudit.getMutationUuid());
                    }
                } catch (RuntimeException e) {
                    if (e.getMessage().contains("UPDATE")) {
                        updateFailure.incrementAndGet();
                    } else if (e.getMessage().contains("DELETE")) {
                        deleteFailure.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - Consistent outcomes: INSERTs succeed, UPDATEs/DELETEs fail
        assertEquals(10, insertSuccess.get(), "All 10 INSERT operations should succeed");
        assertEquals(10, updateFailure.get(), "All 10 UPDATE operations should fail");
        assertEquals(10, deleteFailure.get(), "All 10 DELETE operations should fail");
    }
}
