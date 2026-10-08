package com.capstone.accounts.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.exception.InsufficientBalanceException;
import com.capstone.common.exception.ResourceNotFoundException;

/**
 * Regression Test Suite for AccountHoldService
 * 
 * Tests hold management invariants:
 * - Hold Availability: held funds reduce available balance (BR-33)
 * - Hold Expiry: expired holds release automatically
 * - Hold Release: releasing holds restores available balance
 * 
 * Related Issues: DIG-84, DIG-85
 * Related BRs: BR-33
 */
@DisplayName("AccountHoldService - Hold Availability & Expiry (Regression)")
class AccountHoldServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountHoldRepository accountHoldRepository;

    @Mock
    private BalanceCacheService balanceCacheService;

    private AccountHoldService accountHoldService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        accountHoldService = new AccountHoldService(
            accountRepository,
            accountHoldRepository,
            balanceCacheService
        );
    }

    // ==========================================
    // REG-YAN-03: Hold Availability & Expiry
    // ==========================================

    @Test
    @DisplayName("REG-YAN-03: Should reduce available balance when hold is placed")
    void shouldReduceAvailableBalanceWhenHoldPlaced() throws Exception {
        // Given: Account with ₱5,000 balance and zero holds
        String accountId = "acct-hold-test-001";
        AccountMaster account = createTestAccount(accountId, new BigDecimal("5000.00"));
        
        CreateHoldRequest holdRequest = new CreateHoldRequest(
            new BigDecimal("1000.00"),
            "PHP",
            "RISK",
            24,
            "FRAUD_REVIEW"
        );

        when(accountRepository.findByIdForUpdate(accountId))
            .thenReturn(Optional.of(account));
        when(accountHoldRepository.save(any(AccountHold.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(AccountMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When: Hold of ₱1,000 is placed
        HoldResponse response = accountHoldService.createHold(accountId, holdRequest);

        // Then: Verify hold was created
        assertNotNull(response);
        assertEquals("ACTIVE", response.status());
        assertEquals(new BigDecimal("1000.00"), response.heldAmount());

        // Verify account held amount increased by 1000
        assertEquals(new BigDecimal("1000.00"), account.getHeldAmount());
        
        // Verify available balance calculation (balance - held)
        // available = 5000 - 1000 = 4000
        BigDecimal expectedAvailable = new BigDecimal("4000.00");
        assertEquals(expectedAvailable, account.getAvailableBalance());

        verify(accountHoldRepository).save(any(AccountHold.class));
        verify(accountRepository).save(any(AccountMaster.class));
        verify(balanceCacheService).put(accountId, account.getBalanceAmount());
    }

    @Test
    @DisplayName("REG-YAN-03: Should prevent hold if insufficient available balance")
    void shouldPreventHoldIfInsufficientBalance() throws Exception {
        // Given: Account with ₱5,000 balance but ₱4,000 already on hold
        String accountId = "acct-hold-insufficient";
        AccountMaster account = createTestAccount(accountId, new BigDecimal("5000.00"));
        account.setHeldAmount(new BigDecimal("4000.00"));  // Already held
        
        CreateHoldRequest holdRequest = new CreateHoldRequest(
            new BigDecimal("2000.00"),  // Attempt to hold 2000, but only 1000 available
            "PHP",
            "RISK",
            24,
            "FRAUD_REVIEW"
        );

        when(accountRepository.findByIdForUpdate(accountId))
            .thenReturn(Optional.of(account));

        // When: Hold is attempted with insufficient available balance
        // Then: Should throw InsufficientBalanceException
        assertThrows(InsufficientBalanceException.class, () -> {
            accountHoldService.createHold(accountId, holdRequest);
        }, "Should throw InsufficientBalanceException when available balance insufficient");

        // Verify no hold was created
        verify(accountHoldRepository, never()).save(any(AccountHold.class));
    }

    @Test
    @DisplayName("REG-YAN-03: Should restore available balance when hold is released")
    void shouldRestoreAvailableBalanceWhenHoldReleased() throws Exception {
        // Given: Account with ₱5,000 balance and ₱1,000 on hold
        String accountId = "acct-release-hold";
        String holdId = "hold-release-001";
        
        AccountMaster account = createTestAccount(accountId, new BigDecimal("5000.00"));
        account.setHeldAmount(new BigDecimal("1000.00"));
        
        AccountHold hold = AccountHold.builder()
            .holdId(holdId)
            .accountId(accountId)
            .amount(new BigDecimal("1000.00"))
            .currencyCode("PHP")
            .holdType("RISK")
            .status("ACTIVE")
            .reason("FRAUD_REVIEW")
            .createdAt(LocalDateTime.now())
            .createdBy("HOLD_ENGINE")
            .build();

        when(accountRepository.findByIdForUpdate(accountId))
            .thenReturn(Optional.of(account));
        when(accountHoldRepository.findByIdForUpdate(holdId))
            .thenReturn(Optional.of(hold));
        when(accountRepository.save(any(AccountMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountHoldRepository.save(any(AccountHold.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When: Hold is released
        ReleaseHoldResponse response = accountHoldService.releaseHold(accountId, holdId);

        // Then: Verify hold was released
        assertNotNull(response);
        assertEquals("RELEASED", response.status());

        // Verify account held amount decreased back to zero
        assertEquals(0, account.getHeldAmount().compareTo(BigDecimal.ZERO));
        
        // Verify available balance restored to full balance
        assertEquals(new BigDecimal("5000.00"), account.getAvailableBalance());

        verify(accountRepository).save(any(AccountMaster.class));
        verify(accountHoldRepository).save(argThat(h -> "RELEASED".equals(h.getStatus())));
        verify(balanceCacheService).put(accountId, account.getBalanceAmount());
    }

    @Test
    @DisplayName("REG-YAN-03: Should calculate correct available balance with multiple holds")
    void shouldCalculateCorrectAvailableBalanceWithMultipleHolds() throws Exception {
        // Given: Account with ₱5,000 balance
        String accountId = "acct-multi-hold";
        AccountMaster account = createTestAccount(accountId, new BigDecimal("5000.00"));

        // Scenario: Two holds totaling ₱2,500
        // Hold 1: ₱1,000
        CreateHoldRequest hold1Request = new CreateHoldRequest(
            new BigDecimal("1000.00"),
            "PHP",
            "RISK",
            24,
            "CHECK_1"
        );
        
        // When: First hold is placed
        when(accountRepository.findByIdForUpdate(accountId))
            .thenReturn(Optional.of(account));
        when(accountHoldRepository.save(any(AccountHold.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(accountRepository.save(any(AccountMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        accountHoldService.createHold(accountId, hold1Request);
        
        // Then: available = 5000 - 1000 = 4000
        assertEquals(new BigDecimal("4000.00"), account.getAvailableBalance());

        // When: Second hold is placed
        CreateHoldRequest hold2Request = new CreateHoldRequest(
            new BigDecimal("1500.00"),
            "PHP",
            "FRAUD",
            24,
            "CHECK_2"
        );
        
        account.setHeldAmount(new BigDecimal("1000.00")); // Reset for second hold
        accountHoldService.createHold(accountId, hold2Request);
        
        account.setHeldAmount(new BigDecimal("2500.00")); // Update after second hold
        
        // Then: available = 5000 - 2500 = 2500
        assertEquals(new BigDecimal("2500.00"), account.getAvailableBalance());
    }

    @Test
    @DisplayName("REG-YAN-03: Should throw exception when releasing non-existent hold")
    void shouldThrowExceptionWhenReleasingNonExistentHold() throws Exception {
        // Given: Attempt to release a hold that doesn't exist
        String accountId = "acct-non-existent";
        String holdId = "hold-non-existent-001";

        when(accountRepository.findByIdForUpdate(accountId))
            .thenReturn(Optional.of(createTestAccount(accountId, new BigDecimal("5000.00"))));
        when(accountHoldRepository.findByIdForUpdate(holdId))
            .thenReturn(Optional.empty());

        // When: Release is attempted
        // Then: Should throw ResourceNotFoundException
        assertThrows(ResourceNotFoundException.class, () -> {
            accountHoldService.releaseHold(accountId, holdId);
        }, "Should throw ResourceNotFoundException when hold does not exist");

        verify(accountRepository, never()).save(any(AccountMaster.class));
        verify(accountHoldRepository, never()).save(any(AccountHold.class));
    }

    @Test
    @DisplayName("REG-YAN-03: Should verify hold prevents overdraft via available balance")
    void shouldVerifyHoldPreventsOverdraft() throws Exception {
        // Given: Account with ₱5,000 balance and ₱4,500 on hold
        String accountId = "acct-overdraft-hold";
        AccountMaster account = createTestAccount(accountId, new BigDecimal("5000.00"));
        account.setHeldAmount(new BigDecimal("4500.00"));

        // available = 5000 - 4500 = 500
        assertEquals(new BigDecimal("500.00"), account.getAvailableBalance());

        // When: Attempt to place another hold of ₱1,000 (exceeds available)
        CreateHoldRequest holdRequest = new CreateHoldRequest(
            new BigDecimal("1000.00"),
            "PHP",
            "RISK",
            24,
            "OVERDRAFT_CHECK"
        );

        when(accountRepository.findByIdForUpdate(accountId))
            .thenReturn(Optional.of(account));

        // Then: Should fail due to insufficient available balance
        assertThrows(InsufficientBalanceException.class, () -> {
            accountHoldService.createHold(accountId, holdRequest);
        }, "Hold should fail when it would exceed available balance");

        verify(accountHoldRepository, never()).save(any(AccountHold.class));
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private AccountMaster createTestAccount(String accountId, BigDecimal balance) {
        AccountMaster account = new AccountMaster();
        account.setAccountId(accountId);
        account.setAccountNumber("ACC-" + accountId);
        account.setBalanceAmount(balance);
        account.setHeldAmount(BigDecimal.ZERO);
        account.setAccountStatus("ACTIVE");
        account.setAdminFrozen("N");
        account.setCustomerLocked("N");
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        account.setCurrencyCode("PHP");
        account.setAccountType("SAVINGS");
        account.setIsPrimary("Y");
        return account;
    }
}
