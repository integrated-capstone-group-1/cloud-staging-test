package com.capstone.accounts.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.entity.LedgerEntry;
import com.capstone.accounts.entity.OutboxMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.LedgerEntryRepository;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.accounts.repository.PostingReferenceRepository;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration Test Suite for Atomic Multi-Leg Settlement with Ledger Persistence
 *
 * Verifies that settlement atomicity is maintained: all legs succeed or none succeed.
 * When any leg fails validation, NO accounts are persisted and NO outbox events created.
 *
 * Related Issues: DIG-66
 * Related BRs: BR-10 (Atomic Settlement)
 */
@DisplayName("CoreSettlementService - Atomic Settlement & Ledger (Integration)")
class AtomicSettlementIntegrationTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountHoldRepository accountHoldRepository;
    @Mock
    private PostingReferenceRepository postingReferenceRepository;
    @Mock
    private OutboxMasterRepository outboxMasterRepository;
    @Mock
    private LedgerEntryRepository ledgerEntryRepository;
    @Mock
    private BalanceCacheService balanceCacheService;
    @Mock
    private ObjectMapper objectMapper;

    private CoreSettlementService coreSettlementService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        coreSettlementService = new CoreSettlementService(
            accountRepository, accountHoldRepository, postingReferenceRepository,
            outboxMasterRepository, ledgerEntryRepository, balanceCacheService, objectMapper
        );
        
        when(postingReferenceRepository.findByPostingReferenceId(any())).thenReturn(Optional.empty());
        when(postingReferenceRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(outboxMasterRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {}
        when(accountRepository.saveAll(any())).thenAnswer(i -> {
            Iterable<AccountMaster> accts = i.getArgument(0);
            List<AccountMaster> list = new java.util.ArrayList<>();
            accts.forEach(list::add);
            return list;
        });
    }

    @Test
    @DisplayName("SEAM-YAN-02: Settlement applies atomically - both debit and credit legs")
    void shouldApplyBothLegsAtomically() throws Exception {
        AccountMaster acctA = createAccount("acct-a", new BigDecimal("5000.00"));
        AccountMaster acctB = createAccount("acct-b", new BigDecimal("3000.00"));
        
        doReturn(List.of(acctA, acctB)).when(accountRepository).findAllByIdsForUpdate(any());

        CoreSettlementResponse resp = coreSettlementService.settle(
            new CoreSettlementRequest("posting-ref-1", "TRANSFER", null, List.of(
                new SettlementLegDto("acct-a", "DEBIT", new BigDecimal("1000.00"), "PHP", 1),
                new SettlementLegDto("acct-b", "CREDIT", new BigDecimal("1000.00"), "PHP", 2)
            ))
        );

        assertEquals("SETTLED", resp.status());
        assertTrue(acctA.getBalanceAmount().compareTo(new BigDecimal("5000.00")) < 0);
        assertTrue(acctB.getBalanceAmount().compareTo(new BigDecimal("3000.00")) > 0);
        verify(outboxMasterRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("SEAM-YAN-02: Rejects settlement if destination account is frozen")
    void shouldRejectWhenDestinationFrozen() throws Exception {
        AccountMaster acctA = createAccount("acct-a", new BigDecimal("5000.00"));
        AccountMaster acctB = createAccount("acct-b", new BigDecimal("3000.00"));
        acctB.setAdminFrozen("Y");
        
        doReturn(List.of(acctA, acctB)).when(accountRepository).findAllByIdsForUpdate(any());

        assertThrows(IllegalStateException.class, () -> coreSettlementService.settle(
            new CoreSettlementRequest("posting-ref-frozen", "TRANSFER", null, List.of(
                new SettlementLegDto("acct-a", "DEBIT", new BigDecimal("500.00"), "PHP", 1),
                new SettlementLegDto("acct-b", "CREDIT", new BigDecimal("500.00"), "PHP", 2)
            ))
        ));

        verify(accountRepository, never()).saveAll(any());
        verify(outboxMasterRepository, never()).save(any());
    }

    @Test
    @DisplayName("SEAM-YAN-02: Rejects settlement on insufficient balance - no updates")
    void shouldRejectOnInsufficientBalance() throws Exception {
        AccountMaster acctA = createAccount("acct-a", new BigDecimal("2000.00"));
        AccountMaster acctB = createAccount("acct-b", new BigDecimal("1000.00"));
        
        doReturn(List.of(acctA, acctB)).when(accountRepository).findAllByIdsForUpdate(any());

        assertThrows(com.capstone.common.exception.InsufficientBalanceException.class, () ->
            coreSettlementService.settle(
                new CoreSettlementRequest("posting-ref-insuff", "TRANSFER", null, List.of(
                    new SettlementLegDto("acct-a", "DEBIT", new BigDecimal("5000.00"), "PHP", 1),
                    new SettlementLegDto("acct-b", "CREDIT", new BigDecimal("5000.00"), "PHP", 2)
                ))
            )
        );

        verify(accountRepository, never()).saveAll(any());
        verify(outboxMasterRepository, never()).save(any());
    }

    @Test
    @DisplayName("SEAM-YAN-02: Handles 3-leg settlement atomically")
    void shouldHandle3LegSettlement() throws Exception {
        AccountMaster dist = createAccount("dist", new BigDecimal("10000.00"));
        AccountMaster rec1 = createAccount("rec1", new BigDecimal("1000.00"));
        AccountMaster rec2 = createAccount("rec2", new BigDecimal("1000.00"));
        
        doReturn(List.of(dist, rec1, rec2)).when(accountRepository).findAllByIdsForUpdate(any());

        CoreSettlementResponse resp = coreSettlementService.settle(
            new CoreSettlementRequest("posting-ref-3leg", "DISTRIBUTION", null, List.of(
                new SettlementLegDto("dist", "DEBIT", new BigDecimal("1000.00"), "PHP", 1),
                new SettlementLegDto("rec1", "CREDIT", new BigDecimal("500.00"), "PHP", 2),
                new SettlementLegDto("rec2", "CREDIT", new BigDecimal("500.00"), "PHP", 3)
            ))
        );

        assertEquals("SETTLED", resp.status());
        verify(outboxMasterRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("SEAM-YAN-02: Rejects settlement to inactive account - no persist")
    void shouldRejectToInactiveAccount() throws Exception {
        AccountMaster acctA = createAccount("acct-a", new BigDecimal("5000.00"));
        AccountMaster acctB = createAccount("acct-b", new BigDecimal("3000.00"));
        acctB.setAccountStatus("CLOSED");
        
        doReturn(List.of(acctA, acctB)).when(accountRepository).findAllByIdsForUpdate(any());

        assertThrows(IllegalStateException.class, () -> coreSettlementService.settle(
            new CoreSettlementRequest("posting-ref-closed", "TRANSFER", null, List.of(
                new SettlementLegDto("acct-a", "DEBIT", new BigDecimal("500.00"), "PHP", 1),
                new SettlementLegDto("acct-b", "CREDIT", new BigDecimal("500.00"), "PHP", 2)
            ))
        ));

        verify(accountRepository, never()).saveAll(any());
        verify(outboxMasterRepository, never()).save(any());
    }

    @Test
    @DisplayName("SEAM-YAN-02: Rejects settlement to customer-locked account")
    void shouldRejectToCustomerLockedAccount() throws Exception {
        AccountMaster acctA = createAccount("acct-a", new BigDecimal("5000.00"));
        AccountMaster acctB = createAccount("acct-b", new BigDecimal("3000.00"));
        acctB.setCustomerLocked("Y");
        
        doReturn(List.of(acctA, acctB)).when(accountRepository).findAllByIdsForUpdate(any());

        assertThrows(IllegalStateException.class, () -> coreSettlementService.settle(
            new CoreSettlementRequest("posting-ref-locked", "TRANSFER", null, List.of(
                new SettlementLegDto("acct-a", "DEBIT", new BigDecimal("500.00"), "PHP", 1),
                new SettlementLegDto("acct-b", "CREDIT", new BigDecimal("500.00"), "PHP", 2)
            ))
        ));

        verify(accountRepository, never()).saveAll(any());
        verify(outboxMasterRepository, never()).save(any());
    }

    private AccountMaster createAccount(String id, BigDecimal balance) {
        AccountMaster acc = new AccountMaster();
        acc.setAccountId(id);
        acc.setAccountNumber("ACC-" + id);
        acc.setBalanceAmount(balance);
        acc.setHeldAmount(BigDecimal.ZERO);
        acc.setAccountStatus("ACTIVE");
        acc.setAdminFrozen("N");
        acc.setCustomerLocked("N");
        acc.setCreatedAt(LocalDateTime.now());
        acc.setUpdatedAt(LocalDateTime.now());
        acc.setCurrencyCode("PHP");
        acc.setAccountType("SAVINGS");
        acc.setCustomerId("cust-001");
        acc.setIsPrimary("Y");
        return acc;
    }
}
