package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HoldExpiryJobTest {

    @Mock
    private AccountHoldRepository accountHoldRepository;

    @Mock
    private AccountRepository accountRepository;

    @InjectMocks
    private HoldExpiryJob holdExpiryJob;

    @Test
    void expireHolds_shouldExpireActivePastDueHoldsAndDeductHeldAmount() {
        String accountId = "acct-test-01";
        String holdId = "hold-exp-01";

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId)
                .balanceAmount(new BigDecimal("1000.0000"))
                .heldAmount(new BigDecimal("200.0000"))
                .currencyCode("PHP")
                .accountStatus("ACTIVE")
                .build();

        AccountHold expiredHold = AccountHold.builder()
                .holdId(holdId)
                .accountId(accountId)
                .amount(new BigDecimal("200.0000"))
                .currencyCode("PHP")
                .status("ACTIVE")
                .expiresAt(LocalDateTime.now().minusHours(1))
                .build();

        when(accountHoldRepository.findByStatusAndExpiresAtBefore(eq("ACTIVE"), any(LocalDateTime.class)))
                .thenReturn(List.of(expiredHold));
        when(accountRepository.findByIdForUpdate(accountId))
                .thenReturn(Optional.of(account));

        holdExpiryJob.expireHolds();

        assertEquals("EXPIRED", expiredHold.getStatus());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getHeldAmount()));
        assertEquals("HOLD_EXPIRY_JOB", account.getUpdatedBy());

        verify(accountRepository).save(account);
        verify(accountHoldRepository).save(expiredHold);
    }

    @Test
    void expireHolds_noExpiredHolds_shouldDoNothing() {
        when(accountHoldRepository.findByStatusAndExpiresAtBefore(eq("ACTIVE"), any(LocalDateTime.class)))
                .thenReturn(List.of());

        holdExpiryJob.expireHolds();

        verify(accountRepository, never()).findByIdForUpdate(any());
        verify(accountHoldRepository, never()).save(any());
    }
}
