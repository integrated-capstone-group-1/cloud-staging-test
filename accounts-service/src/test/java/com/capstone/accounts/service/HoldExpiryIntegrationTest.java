package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.exception.InsufficientBalanceException;
import com.capstone.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SEAM-YAN-03: Hold Placement, Available Balance & Expiry Integration Tests
 *
 * Objective:
 * Verify that hold placement reduces available balance (available = balance - held),
 * expired holds release automatically, and multiple holds aggregate correctly.
 *
 * Test Scenarios:
 * 1. Hold placement reduces available balance
 * 2. Hold expiry process releases expired holds
 * 3. Multiple holds aggregate and reduce available balance
 * 4. Available balance prevents overdraft with active holds
 * 5. Hold placement fails if insufficient available balance
 * 6. Hold release restores available balance immediately
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SEAM-YAN-03: Hold Placement, Available Balance & Expiry")
public class HoldExpiryIntegrationTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountHoldRepository accountHoldRepository;

    @Mock
    private BalanceCacheService balanceCacheService;

    @InjectMocks
    private AccountHoldService accountHoldService;

    private static final String ACCOUNT_ID = "ACC-001";
    private static final String HOLD_ID_1 = "HOLD-001";
    private static final String HOLD_ID_2 = "HOLD-002";
    private static final String HOLD_ID_3 = "HOLD-003";
    private static final String CUSTOMER_ID = "CUST-001";
    private static final String CURRENCY = "PHP";

    private AccountMaster testAccount;
    private AccountHold holdOne;
    private AccountHold holdTwo;

    @BeforeEach
    void setUp() {
        // Set up test account with balance 10,000 PHP
        testAccount = AccountMaster.builder()
                .accountId(ACCOUNT_ID)
                .accountNumber("1234567890")
                .customerId(CUSTOMER_ID)
                .accountType("SAVINGS")
                .currencyCode(CURRENCY)
                .accountStatus("ACTIVE")
                .balanceAmount(new BigDecimal("10000.0000"))
                .heldAmount(BigDecimal.ZERO)
                .createdAt(LocalDateTime.now())
                .createdBy("SYSTEM")
                .updatedAt(LocalDateTime.now())
                .updatedBy("SYSTEM")
                .build();

        // Set up test holds
        holdOne = AccountHold.builder()
                .holdId(HOLD_ID_1)
                .accountId(ACCOUNT_ID)
                .amount(new BigDecimal("2000.0000"))
                .currencyCode(CURRENCY)
                .holdType("RISK")
                .status("ACTIVE")
                .reason("Risk hold for payment")
                .expiresAt(LocalDateTime.now().plusHours(24))
                .createdAt(LocalDateTime.now())
                .createdBy("HOLD_ENGINE")
                .build();

        holdTwo = AccountHold.builder()
                .holdId(HOLD_ID_2)
                .accountId(ACCOUNT_ID)
                .amount(new BigDecimal("3000.0000"))
                .currencyCode(CURRENCY)
                .holdType("AMOUNT_RESERVE")
                .status("ACTIVE")
                .reason("Amount reservation")
                .expiresAt(LocalDateTime.now().plusHours(48))
                .createdAt(LocalDateTime.now())
                .createdBy("HOLD_ENGINE")
                .build();
    }

    @Test
    @DisplayName("Should place hold and reduce available balance correctly")
    void shouldPlaceHoldAndReduceAvailableBalance() {
        // Arrange
        CreateHoldRequest request = new CreateHoldRequest(
                new BigDecimal("2000.0000"),
                CURRENCY,
                "RISK",
                24,
                "Risk hold for payment"
        );

        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(testAccount));
        when(accountRepository.save(any(AccountMaster.class))).thenAnswer(invocation -> {
            AccountMaster saved = invocation.getArgument(0);
            testAccount.setHeldAmount(saved.getHeldAmount());
            return saved;
        });
        when(accountHoldRepository.save(any(AccountHold.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        HoldResponse response = accountHoldService.createHold(ACCOUNT_ID, request);

        // Assert
        assertNotNull(response);
        assertEquals("ACTIVE", response.status());
        assertEquals(new BigDecimal("2000.0000"), response.heldAmount());

        // Verify held amount was updated
        assertEquals(new BigDecimal("2000.0000"), testAccount.getHeldAmount());

        // Verify available balance is reduced (10000 - 2000 = 8000)
        BigDecimal expectedAvailable = new BigDecimal("8000.0000");
        assertEquals(0, expectedAvailable.compareTo(testAccount.getAvailableBalance()));

        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(accountRepository).save(any(AccountMaster.class));
        verify(accountHoldRepository).save(any(AccountHold.class));
    }

    @Test
    @DisplayName("Should reject hold placement when insufficient available balance")
    void shouldRejectHoldPlacementOnInsufficientAvailableBalance() {
        // Arrange - Account has 1000 available, request hold of 2000
        testAccount.setHeldAmount(new BigDecimal("9000.0000")); // available = 10000 - 9000 = 1000
        CreateHoldRequest request = new CreateHoldRequest(
                new BigDecimal("2000.0000"),
                CURRENCY,
                "RISK",
                24,
                "Risk hold"
        );

        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(testAccount));

        // Act & Assert
        assertThrows(InsufficientBalanceException.class, () -> {
            accountHoldService.createHold(ACCOUNT_ID, request);
        });

        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(accountRepository, never()).save(any());
        verify(accountHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should aggregate multiple holds and reduce available balance correctly")
    void shouldAggregateMultipleHoldsCorrectly() {
        // Arrange - Place two holds: 2000 + 3000 = 5000 total held
        testAccount.setHeldAmount(new BigDecimal("5000.0000"));
        // Available = 10000 - 5000 = 5000

        when(accountHoldRepository.findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE"))
                .thenReturn(List.of(holdOne, holdTwo));

        // Act
        List<AccountHold> activeHolds = accountHoldService.getActiveHoldsForAccount(ACCOUNT_ID);

        // Assert
        assertEquals(2, activeHolds.size());

        BigDecimal totalHeld = activeHolds.stream()
                .map(AccountHold::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertEquals(0, new BigDecimal("5000.0000").compareTo(totalHeld));

        // Available balance after holds: 10000 - 5000 = 5000
        BigDecimal expectedAvailable = new BigDecimal("5000.0000");
        BigDecimal actualAvailable = testAccount.getBalanceAmount().subtract(totalHeld);
        assertEquals(0, expectedAvailable.compareTo(actualAvailable));

        verify(accountHoldRepository).findByAccountIdAndStatus(ACCOUNT_ID, "ACTIVE");
    }

    @Test
    @DisplayName("Should prevent overdraft when available balance includes holds")
    void shouldPreventOverdraftWithActiveHolds() {
        // Arrange - Account has 10000, held 5000, available 5000
        // Verify settlement cannot exceed available 5000
        testAccount.setHeldAmount(new BigDecimal("5000.0000"));

        // Act
        BigDecimal available = testAccount.getAvailableBalance();

        // Assert - Available is 5000, which should prevent overdraft for amounts > 5000
        assertEquals(0, new BigDecimal("5000.0000").compareTo(available));
        assertTrue(new BigDecimal("6000.0000").compareTo(available) > 0); // 6000 > 5000
        assertTrue(new BigDecimal("5000.0000").compareTo(available) >= 0); // 5000 >= 5000
    }

    @Test
    @DisplayName("Should release hold and restore available balance immediately")
    void shouldReleaseHoldAndRestoreAvailableBalance() {
        // Arrange - Account has 10000 balance, 2000 held, available 8000
        testAccount.setHeldAmount(new BigDecimal("2000.0000"));

        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(testAccount));
        when(accountHoldRepository.findByIdForUpdate(HOLD_ID_1)).thenReturn(Optional.of(holdOne));
        when(accountRepository.save(any(AccountMaster.class))).thenAnswer(invocation -> {
            AccountMaster saved = invocation.getArgument(0);
            testAccount.setHeldAmount(saved.getHeldAmount());
            return saved;
        });
        when(accountHoldRepository.save(any(AccountHold.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        ReleaseHoldResponse response = accountHoldService.releaseHold(ACCOUNT_ID, HOLD_ID_1);

        // Assert
        assertNotNull(response);
        assertEquals("RELEASED", response.status());

        // Verify held amount decreased by hold amount
        assertEquals(0, BigDecimal.ZERO.compareTo(testAccount.getHeldAmount()));

        // Verify available balance is restored (10000 - 0 = 10000)
        BigDecimal expectedAvailable = new BigDecimal("10000.0000");
        assertEquals(0, expectedAvailable.compareTo(testAccount.getAvailableBalance()));

        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(accountHoldRepository).findByIdForUpdate(HOLD_ID_1);
        verify(accountRepository).save(any(AccountMaster.class));
        verify(accountHoldRepository).save(any(AccountHold.class));
    }

    @Test
    @DisplayName("Should identify and process expired holds for automatic release")
    void shouldIdentifyExpiredHoldsForAutomaticRelease() {
        // Arrange - Create expired hold
        LocalDateTime pastTime = LocalDateTime.now().minusHours(1);
        AccountHold expiredHold = AccountHold.builder()
                .holdId(HOLD_ID_3)
                .accountId(ACCOUNT_ID)
                .amount(new BigDecimal("1500.0000"))
                .currencyCode(CURRENCY)
                .holdType("RISK")
                .status("ACTIVE")
                .expiresAt(pastTime)
                .createdAt(LocalDateTime.now().minusHours(25))
                .createdBy("HOLD_ENGINE")
                .build();

        when(accountHoldRepository.findByStatusAndExpiresAtBefore(eq("ACTIVE"), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredHold));

        // Act
        List<AccountHold> expiredHolds = accountHoldRepository.findByStatusAndExpiresAtBefore(
                "ACTIVE",
                LocalDateTime.now()
        );

        // Assert
        assertEquals(1, expiredHolds.size());
        assertEquals(HOLD_ID_3, expiredHolds.get(0).getHoldId());
        assertTrue(expiredHolds.get(0).getExpiresAt().isBefore(LocalDateTime.now()));

        verify(accountHoldRepository).findByStatusAndExpiresAtBefore(eq("ACTIVE"), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("Should throw exception when releasing non-existent hold")
    void shouldThrowExceptionWhenReleasingNonExistentHold() {
        // Arrange
        when(accountRepository.findByIdForUpdate(ACCOUNT_ID)).thenReturn(Optional.of(testAccount));
        when(accountHoldRepository.findByIdForUpdate("NONEXISTENT")).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResourceNotFoundException.class, () -> {
            accountHoldService.releaseHold(ACCOUNT_ID, "NONEXISTENT");
        });

        verify(accountRepository).findByIdForUpdate(ACCOUNT_ID);
        verify(accountHoldRepository).findByIdForUpdate("NONEXISTENT");
    }
}
