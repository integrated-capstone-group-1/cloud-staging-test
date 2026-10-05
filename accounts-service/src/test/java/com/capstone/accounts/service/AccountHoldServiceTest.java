package com.capstone.accounts.service;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.exception.InsufficientBalanceException;

@ExtendWith(MockitoExtension.class)
class AccountHoldServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private AccountHoldRepository accountHoldRepository;

    @Mock
    private BalanceCacheService balanceCacheService;

    @InjectMocks
    private AccountHoldService accountHoldService;

    private AccountMaster testAccount;

    @BeforeEach
    void setUp() {
        testAccount = new AccountMaster();
        testAccount.setAccountId("ACC-1001");
        testAccount.setAccountStatus("ACTIVE");
        testAccount.setCurrencyCode("USD");
        testAccount.setBalanceAmount(new BigDecimal("1000.00"));
        testAccount.setHeldAmount(new BigDecimal("100.00")); // Available = 900.00
    }

    @Test
    @DisplayName("Should create hold successfully when sufficient available balance exists")
    void createHold_Success() {
        CreateHoldRequest request = new CreateHoldRequest(
            new BigDecimal("200.00"),
            "USD",
            "RISK",
            24,
            "Risk Assessment"
        );

        when(accountRepository.findByIdForUpdate("ACC-1001")).thenReturn(Optional.of(testAccount));
        when(accountHoldRepository.save(any(AccountHold.class))).thenAnswer(i -> i.getArgument(0));

        HoldResponse response = accountHoldService.createHold("ACC-1001", request);

        assertNotNull(response);
        assertEquals("ACTIVE", response.status());
        assertEquals(new BigDecimal("200.00"), response.heldAmount());
        assertEquals(new BigDecimal("300.00"), testAccount.getHeldAmount()); // 100 + 200

        verify(balanceCacheService).put(eq("ACC-1001"), eq(new BigDecimal("1000.00")));
    }

    @Test
    @DisplayName("Should fail hold creation when requested amount exceeds available balance")
    void createHold_InsufficientBalance() {
        CreateHoldRequest request = new CreateHoldRequest(
            new BigDecimal("950.00"), // Available is 900.00
            "USD",
            "RISK",
            24,
            "Risk Assessment"
        );

        when(accountRepository.findByIdForUpdate("ACC-1001")).thenReturn(Optional.of(testAccount));

        assertThrows(InsufficientBalanceException.class, () -> 
            accountHoldService.createHold("ACC-1001", request)
        );

        verify(accountHoldRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should release hold and restore available balance")
    void releaseHold_Success() {
        AccountHold hold = AccountHold.builder()
                .holdId("HOLD-001")
                .accountId("ACC-1001")
                .amount(new BigDecimal("100.00"))
                .status("ACTIVE")
                .build();

        when(accountRepository.findByIdForUpdate("ACC-1001")).thenReturn(Optional.of(testAccount));
        when(accountHoldRepository.findByIdForUpdate("HOLD-001")).thenReturn(Optional.of(hold));

        ReleaseHoldResponse response = accountHoldService.releaseHold("ACC-1001", "HOLD-001");

        assertNotNull(response);
        assertEquals("RELEASED", response.status());
        assertEquals("RELEASED", hold.getStatus());
        assertEquals(0, testAccount.getHeldAmount().compareTo(BigDecimal.ZERO));

        verify(balanceCacheService).put(eq("ACC-1001"), eq(new BigDecimal("1000.00")));
    }
}