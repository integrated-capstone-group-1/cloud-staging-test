package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountHold;
import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.exception.InsufficientBalanceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountHoldEngineTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountHoldRepository accountHoldRepository;
    @Mock
    private BalanceCacheService balanceCacheService;

    @InjectMocks
    private AccountHoldService accountHoldService;

    @Test
    void createHold_shouldPlaceHoldSuccessfully() {
        String accountId = "ACC-001";
        CreateHoldRequest request = new CreateHoldRequest(
                new BigDecimal("100.00"), "USD", "RISK", 24, "Fraud prevention");

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId).balanceAmount(new BigDecimal("1000.00"))
                .heldAmount(BigDecimal.ZERO).currencyCode("USD").accountStatus("ACTIVE").build();

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(accountHoldRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HoldResponse response = accountHoldService.createHold(accountId, request);

        assertNotNull(response);
        assertEquals("ACTIVE", response.status());
        assertEquals(0, new BigDecimal("100.00").compareTo(account.getHeldAmount()));
        verify(balanceCacheService).put(accountId, new BigDecimal("1000.00"));
    }

    @Test
    void createHold_shouldRejectInsufficientFunds() {
        String accountId = "ACC-002";
        CreateHoldRequest request = new CreateHoldRequest(
                new BigDecimal("2000.00"), "USD", "RISK", 24, "High risk");

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId).balanceAmount(new BigDecimal("1000.00"))
                .heldAmount(BigDecimal.ZERO).currencyCode("USD").accountStatus("ACTIVE").build();

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        assertThrows(InsufficientBalanceException.class,
                () -> accountHoldService.createHold(accountId, request));
    }

    @Test
    void createHold_shouldRespectHeldAmountInAvailableBalance() {
        String accountId = "ACC-003";
        CreateHoldRequest request = new CreateHoldRequest(
                new BigDecimal("400.00"), "USD", "RISK", 24, "Additional");

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId).balanceAmount(new BigDecimal("1000.00"))
                .heldAmount(new BigDecimal("700.00")).currencyCode("USD").accountStatus("ACTIVE").build();

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));

        assertThrows(InsufficientBalanceException.class,
                () -> accountHoldService.createHold(accountId, request));
    }

    @Test
    void releaseHold_shouldDecreaseHeldAmountSuccessfully() {
        String accountId = "ACC-004";
        String holdId = "HOLD-001";

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId).balanceAmount(new BigDecimal("1000.00"))
                .heldAmount(new BigDecimal("100.00")).currencyCode("USD").accountStatus("ACTIVE").build();

        AccountHold hold = AccountHold.builder()
                .holdId(holdId).accountId(accountId).amount(new BigDecimal("100.00"))
                .status("ACTIVE").build();

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(accountHoldRepository.findByIdForUpdate(holdId)).thenReturn(Optional.of(hold));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountHoldRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReleaseHoldResponse response = accountHoldService.releaseHold(accountId, holdId);

        assertEquals("RELEASED", response.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(account.getHeldAmount()));
        verify(balanceCacheService).put(accountId, new BigDecimal("1000.00"));
    }

    @Test
    void releaseHold_shouldRejectReleaseOfInactiveHold() {
        String accountId = "ACC-005";
        String holdId = "HOLD-002";

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId).balanceAmount(new BigDecimal("1000.00"))
                .heldAmount(BigDecimal.ZERO).build();

        AccountHold hold = AccountHold.builder()
                .holdId(holdId).accountId(accountId).status("RELEASED").build();

        when(accountRepository.findByIdForUpdate(accountId)).thenReturn(Optional.of(account));
        when(accountHoldRepository.findByIdForUpdate(holdId)).thenReturn(Optional.of(hold));

        assertThrows(IllegalStateException.class,
                () -> accountHoldService.releaseHold(accountId, holdId));
    }

    @Test
    void getActiveHoldsForAccount_shouldReturnActiveHoldsOnly() {
        String accountId = "ACC-006";
        
        AccountHold activeHold1 = AccountHold.builder()
                .holdId("HOLD-003").accountId(accountId).amount(new BigDecimal("50.00"))
                .status("ACTIVE").build();

        AccountHold activeHold2 = AccountHold.builder()
                .holdId("HOLD-004").accountId(accountId).amount(new BigDecimal("75.00"))
                .status("ACTIVE").build();

        when(accountHoldRepository.findByAccountIdAndStatus(accountId, "ACTIVE"))
                .thenReturn(List.of(activeHold1, activeHold2));

        List<AccountHold> holds = accountHoldService.getActiveHoldsForAccount(accountId);

        assertEquals(2, holds.size());
        assertTrue(holds.stream().allMatch(h -> "ACTIVE".equals(h.getStatus())));
    }

    @Test
    void getHold_shouldReturnHoldById() {
        String holdId = "HOLD-005";
        
        AccountHold hold = AccountHold.builder()
                .holdId(holdId).accountId("ACC-007").amount(new BigDecimal("200.00"))
                .status("ACTIVE").build();

        when(accountHoldRepository.findById(holdId)).thenReturn(Optional.of(hold));

        AccountHold result = accountHoldService.getHold(holdId);

        assertNotNull(result);
        assertEquals(holdId, result.getHoldId());
        assertEquals(new BigDecimal("200.00"), result.getAmount());
    }
}