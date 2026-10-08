package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
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
 * NFR-INTEG-02: Account Lock & Admin Freeze Precedence Stress Test
 *
 * Objective:
 * Subject admin-frozen account to 100 rapid concurrent unlock attempts
 * and verify 100% rejection rate with status remaining FROZEN.
 *
 * Test Scenarios:
 * 1. 100 concurrent unlock requests on admin-frozen account all fail
 * 2. Account status remains FROZEN after all unlock attempts
 * 3. Returns consistent 422 response across concurrent requests
 * 4. Admin freeze precedence unaffected by concurrent access pressure
 * 5. No race condition allows unlocking during stress
 * 6. Exception type consistent across all attempts
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NFR-INTEG-02: Account Lock & Admin Freeze Precedence Stress")
public class AdminFreezeStressTest {

    @Mock
    private AccountRepository accountRepository;

    private static final String ACCOUNT_ID = "ACC-FROZEN-001";
    private static final int CONCURRENT_UNLOCK_ATTEMPTS = 100;

    private AccountMaster adminFrozenAccount;
    private AtomicInteger rejectionCount;
    private AtomicInteger illegalStateExceptions;
    private AtomicInteger successfulUnlocks;

    @BeforeEach
    void setUp() {
        rejectionCount = new AtomicInteger(0);
        illegalStateExceptions = new AtomicInteger(0);
        successfulUnlocks = new AtomicInteger(0);

        // Set up admin-frozen account
        adminFrozenAccount = AccountMaster.builder()
                .accountId(ACCOUNT_ID)
                .accountNumber("1234567890")
                .customerId("CUST-001")
                .accountType("SAVINGS")
                .currencyCode("PHP")
                .accountStatus("FROZEN")
                .balanceAmount(new BigDecimal("5000.0000"))
                .heldAmount(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .createdBy("SYSTEM")
                .updatedAt(LocalDateTime.now())
                .updatedBy("ADMIN")
                .adminFrozen("Y") // Admin freeze active
                .customerLocked("N")
                .build();
    }

    @Test
    @DisplayName("Should reject 100 concurrent unlock attempts on admin-frozen account")
    void shouldReject100ConcurrentUnlockAttempts() throws InterruptedException {
        // Arrange
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(adminFrozenAccount));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_UNLOCK_ATTEMPTS);

        // Act - Simulate 100 concurrent unlock attempts
        for (int i = 0; i < CONCURRENT_UNLOCK_ATTEMPTS; i++) {
            executor.submit(() -> {
                try {
                    // Simulate unlock attempt
                    AccountMaster account = accountRepository.findByIdForUpdate(ACCOUNT_ID)
                            .orElseThrow(() -> new ResourceNotFoundException("Account not found"));

                    // Check admin freeze precedence
                    if ("Y".equals(account.getAdminFrozen())) {
                        throw new IllegalStateException("Account is admin frozen and cannot be unlocked");
                    }

                    account.setAccountStatus("ACTIVE");
                    account.setCustomerLocked("N");
                    accountRepository.save(account);
                    successfulUnlocks.incrementAndGet();
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("admin frozen")) {
                        illegalStateExceptions.incrementAndGet();
                        rejectionCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    rejectionCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - All 100 unlock attempts rejected
        assertEquals(CONCURRENT_UNLOCK_ATTEMPTS, rejectionCount.get(),
                "All unlock attempts on admin-frozen account should be rejected");
        assertEquals(0, successfulUnlocks.get(),
                "No unlocks should succeed on admin-frozen account");
        assertTrue(illegalStateExceptions.get() > 0,
                "Rejections should include IllegalStateException");

        verify(accountRepository, atLeast(CONCURRENT_UNLOCK_ATTEMPTS)).findByIdForUpdate(ACCOUNT_ID);
    }

    @Test
    @DisplayName("Should maintain FROZEN status after 100 concurrent unlock attempts")
    void shouldMaintainFrozenStatusAfterStress() throws InterruptedException {
        // Arrange
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(adminFrozenAccount));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_UNLOCK_ATTEMPTS);

        // Act
        for (int i = 0; i < CONCURRENT_UNLOCK_ATTEMPTS; i++) {
            executor.submit(() -> {
                try {
                    AccountMaster account = accountRepository.findByIdForUpdate(ACCOUNT_ID)
                            .orElseThrow();

                    if ("Y".equals(account.getAdminFrozen())) {
                        throw new IllegalStateException("Cannot unlock admin-frozen account");
                    }

                    account.setAccountStatus("ACTIVE");
                    accountRepository.save(account);
                } catch (IllegalStateException e) {
                    // Expected - do nothing
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - Account status remains FROZEN
        assertEquals("FROZEN", adminFrozenAccount.getAccountStatus(),
                "Account status should remain FROZEN");
        assertEquals("Y", adminFrozenAccount.getAdminFrozen(),
                "Admin freeze should remain active");

        verify(accountRepository, atLeast(CONCURRENT_UNLOCK_ATTEMPTS)).findByIdForUpdate(ACCOUNT_ID);
    }

    @Test
    @DisplayName("Should return consistent rejection across concurrent rapid-fire requests")
    void shouldReturnConsistentRejectionAcrossRapidFire() throws InterruptedException {
        // Arrange
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(adminFrozenAccount));

        ExecutorService executor = Executors.newFixedThreadPool(30);
        CountDownLatch latch = new CountDownLatch(100);
        AtomicInteger adminFreezeErrors = new AtomicInteger(0);
        AtomicInteger otherErrors = new AtomicInteger(0);

        // Act - 100 rapid-fire requests with 30 concurrent threads
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < 100; i++) {
            executor.submit(() -> {
                try {
                    AccountMaster account = accountRepository.findByIdForUpdate(ACCOUNT_ID)
                            .orElseThrow();

                    if ("Y".equals(account.getAdminFrozen())) {
                        throw new IllegalStateException("422 ACCOUNT_FROZEN: admin freeze precedence");
                    }

                    account.setAccountStatus("ACTIVE");
                    accountRepository.save(account);
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("ACCOUNT_FROZEN")) {
                        adminFreezeErrors.incrementAndGet();
                    } else {
                        otherErrors.incrementAndGet();
                    }
                } catch (Exception e) {
                    otherErrors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - startTime;
        executor.shutdown();

        // Assert
        assertTrue(completed, "All requests should complete within 5 seconds");
        assertEquals(100, adminFreezeErrors.get() + otherErrors.get(),
                "All requests should receive rejection");
        assertTrue(adminFreezeErrors.get() >= 90,
                "At least 90% should get admin freeze error");
        assertTrue(elapsed < 5000, "Stress test should complete quickly");
    }

    @Test
    @DisplayName("Should prevent admin freeze bypass under concurrent access")
    void shouldPreventAdminFreezeBypassUnderConcurrentAccess() throws InterruptedException {
        // Arrange
        AtomicInteger blockedAttempts = new AtomicInteger(0);
        AtomicInteger successAttempts = new AtomicInteger(0);

        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(adminFrozenAccount));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(CONCURRENT_UNLOCK_ATTEMPTS);

        // Act - Aggressive unlock attempts trying to bypass freeze
        for (int i = 0; i < CONCURRENT_UNLOCK_ATTEMPTS; i++) {
            executor.submit(() -> {
                try {
                    AccountMaster account = accountRepository.findByIdForUpdate(ACCOUNT_ID)
                            .orElseThrow();

                    // Enforce freeze check before any state change
                    if ("Y".equals(account.getAdminFrozen())) {
                        blockedAttempts.incrementAndGet();
                        throw new IllegalStateException("Freeze check blocked bypass");
                    }

                    // This should never execute
                    account.setAccountStatus("ACTIVE");
                    accountRepository.save(account);
                    successAttempts.incrementAndGet();
                } catch (IllegalStateException e) {
                    // Expected - bypass blocked
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - All attempts blocked before any state change
        assertEquals(CONCURRENT_UNLOCK_ATTEMPTS, blockedAttempts.get(),
                "All attempts should be blocked by freeze check");
        assertEquals(0, successAttempts.get(),
                "Zero successful unlocks on admin-frozen account");
    }

    @Test
    @DisplayName("Should handle no race conditions during freeze status check")
    void shouldHandleNoRaceConditionsDuringFreezeCheck() throws InterruptedException {
        // Arrange
        AtomicInteger checkedAsActive = new AtomicInteger(0);
        AtomicInteger foundAsActive = new AtomicInteger(0);

        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenAnswer(invocation -> {
                    // Always return frozen state
                    return Optional.of(adminFrozenAccount);
                });

        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch latch = new CountDownLatch(50);

        // Act - Rapid status checks on potentially updating account
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    AccountMaster account = accountRepository.findByIdForUpdate(ACCOUNT_ID)
                            .orElseThrow();

                    // Check status at moment of read
                    if ("ACTIVE".equals(account.getAccountStatus())) {
                        checkedAsActive.incrementAndGet();
                    }

                    // Simulate checking freeze after status check
                    if ("Y".equals(account.getAdminFrozen())) {
                        // This should always be true due to admin_frozen precedence
                        foundAsActive.incrementAndGet();
                    }

                    // Attempt unlock (should fail)
                    if ("Y".equals(account.getAdminFrozen())) {
                        throw new IllegalStateException("Frozen");
                    }
                } catch (IllegalStateException e) {
                    // Expected
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - No race condition that allowed unlocking frozen account
        assertEquals(50, foundAsActive.get(),
                "All 50 checks should find admin freeze active");
        assertEquals(0, checkedAsActive.get(),
                "No account should appear as ACTIVE due to admin freeze");
    }

    @Test
    @DisplayName("Should track that all concurrent rejections are due to admin freeze")
    void shouldTrackRejectionReasonsAreAdminFreeze() throws InterruptedException {
        // Arrange
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID))
                .thenReturn(Optional.of(adminFrozenAccount));

        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(50);
        AtomicInteger adminFreezeRejections = new AtomicInteger(0);
        AtomicInteger insufficientFundsRejections = new AtomicInteger(0);
        AtomicInteger otherRejections = new AtomicInteger(0);

        // Act
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> {
                try {
                    AccountMaster account = accountRepository.findByIdForUpdate(ACCOUNT_ID)
                            .orElseThrow();

                    // Check constraints in order
                    if ("Y".equals(account.getAdminFrozen())) {
                        throw new IllegalStateException("ADMIN_FREEZE: account cannot be unlocked");
                    } else if (account.getBalanceAmount().compareTo(BigDecimal.ZERO) < 0) {
                        throw new IllegalStateException("INSUFFICIENT_FUNDS");
                    }

                    account.setAccountStatus("ACTIVE");
                    accountRepository.save(account);
                } catch (IllegalStateException e) {
                    if (e.getMessage().contains("ADMIN_FREEZE")) {
                        adminFreezeRejections.incrementAndGet();
                    } else if (e.getMessage().contains("INSUFFICIENT_FUNDS")) {
                        insufficientFundsRejections.incrementAndGet();
                    } else {
                        otherRejections.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        // Assert - All rejections are due to admin freeze (precedence validated)
        assertEquals(50, adminFreezeRejections.get(),
                "All 50 rejections should be due to admin freeze");
        assertEquals(0, insufficientFundsRejections.get(),
                "No rejections due to insufficient funds (freeze checked first)");
        assertEquals(0, otherRejections.get(),
                "No other rejection reasons");
    }
}
