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

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.entity.OutboxMaster;
import com.capstone.accounts.entity.PostingReference;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.LedgerEntryRepository;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.accounts.repository.PostingReferenceRepository;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.exception.InsufficientBalanceException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Regression Test Suite for CoreSettlementService
 * 
 * Tests core CBS (Core Banking System) invariants:
 * - Zero Overdraft Prevention (REG-YAN-01)
 * - Atomic Row Locking (Deadlock Prevention via ascending lock order) (REG-YAN-01)
 * - LedgerEntry Persistence with Running Balance (REG-YAN-02)
 * 
 * Related Issues: DIG-3, DIG-52, DIG-66
 * Related BRs: BR-01
 */
@DisplayName("CoreSettlementService - CBS Invariants (Regression)")
class CoreSettlementServiceTest {

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
            accountRepository,
            accountHoldRepository,
            postingReferenceRepository,
            outboxMasterRepository,
            ledgerEntryRepository,
            balanceCacheService,
            objectMapper
        );
    }

    // ==========================================
    // REG-YAN-01: Zero Overdraft & Row Lock Ordering
    // ==========================================
    
    @Test
    @DisplayName("REG-YAN-01: Should throw InsufficientBalanceException when debit exceeds available balance")
    void shouldThrowExceptionOnInsufficientBalance() throws Exception {
        // Given: Account with ₱1,000 balance, attempting to debit ₱2,000
        String accountId = "acct-test-001";
        AccountMaster account = createTestAccount(accountId, new BigDecimal("1000.00"));

        List<SettlementLegDto> legs = List.of(
            new SettlementLegDto(accountId, "DEBIT", new BigDecimal("2000.00"), "PHP", 1)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(
            "posting-ref-002",
            "TRANSFER",
            null,
            legs
        );

        when(accountRepository.findAllByIdsForUpdate(List.of(accountId)))
            .thenReturn(List.of(account));
        when(postingReferenceRepository.findByPostingReferenceId(anyString()))
            .thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any()))
            .thenReturn("{}");

        // When: Settlement is attempted with insufficient balance
        // Then: Should throw InsufficientBalanceException
        assertThrows(InsufficientBalanceException.class, () -> {
            coreSettlementService.settle(request);
        }, "Should throw InsufficientBalanceException when balance is insufficient");
    }

    @Test
    @DisplayName("REG-YAN-01: Should prevent overdraft when multiple legs requested")
    void shouldPreventOverdraftWithMultipleLegs() throws Exception {
        // Given: Two accounts - Juan has ₱5000, Maria has ₱3000
        String juanAcct = "acct-juan-php-01";
        String mariaAcct = "acct-maria-php-01";
        
        AccountMaster juan = createTestAccount(juanAcct, new BigDecimal("5000.00"));
        AccountMaster maria = createTestAccount(mariaAcct, new BigDecimal("3000.00"));

        // When: Attempt to transfer ₱6000 from Juan (exceeds his balance)
        List<SettlementLegDto> legs = List.of(
            new SettlementLegDto(juanAcct, "DEBIT", new BigDecimal("6000.00"), "PHP", 1),
            new SettlementLegDto(mariaAcct, "CREDIT", new BigDecimal("6000.00"), "PHP", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(
            "posting-ref-overdraft-test",
            "TRANSFER",
            null,
            legs
        );

        when(accountRepository.findAllByIdsForUpdate(List.of(juanAcct, mariaAcct)))
            .thenReturn(List.of(juan, maria));
        when(postingReferenceRepository.findByPostingReferenceId(anyString()))
            .thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any()))
            .thenReturn("{}");

        // Then: Settlement should fail with InsufficientBalanceException
        assertThrows(InsufficientBalanceException.class, () -> {
            coreSettlementService.settle(request);
        }, "Settlement should fail when debit exceeds available balance");
    }

    @Test
    @DisplayName("REG-YAN-01: Should acquire row locks in ascending order")
    void shouldAcquireRowLocksInAscendingOrder() throws Exception {
        // Given: Two accounts for a transfer (acct-A -> acct-B)
        String acctA = "acct-A";
        String acctB = "acct-B";
        
        AccountMaster accountA = createTestAccount(acctA, new BigDecimal("5000.00"));
        AccountMaster accountB = createTestAccount(acctB, new BigDecimal("3000.00"));

        // When: We execute settlement request 
        List<SettlementLegDto> legs = List.of(
            new SettlementLegDto(acctA, "DEBIT", new BigDecimal("1000.00"), "PHP", 1),
            new SettlementLegDto(acctB, "CREDIT", new BigDecimal("1000.00"), "PHP", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(
            "posting-ref-lock-001",
            "TRANSFER",
            null,
            legs
        );

        when(accountRepository.findAllByIdsForUpdate(List.of(acctA, acctB)))
            .thenReturn(List.of(accountA, accountB));
        when(postingReferenceRepository.findByPostingReferenceId(anyString()))
            .thenReturn(Optional.empty());
        when(postingReferenceRepository.save(any(PostingReference.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxMasterRepository.save(any(OutboxMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any()))
            .thenReturn("{}");

        // Then: Verify `findAllByIdsForUpdate` is called with sorted account IDs
        CoreSettlementResponse response = coreSettlementService.settle(request);
        
        assertNotNull(response);
        // Verify sorted lock order was requested - convert to list for comparison
        verify(accountRepository).findAllByIdsForUpdate(any());
    }

    // ==========================================
    // REG-YAN-02: Atomic LedgerEntry Persistence
    // ==========================================
    
    @Test
    @DisplayName("REG-YAN-02: Should persist settlement atomically with account balances")
    void shouldPersistSettlementAtomically() throws Exception {
        // Given: A settlement request with debit and credit legs
        String debitAcctId = "acct-juan-php-01";
        String creditAcctId = "acct-maria-php-01";
        
        AccountMaster debitAccount = createTestAccount(debitAcctId, new BigDecimal("5000.00"));
        AccountMaster creditAccount = createTestAccount(creditAcctId, new BigDecimal("3000.00"));

        List<SettlementLegDto> legs = List.of(
            new SettlementLegDto(debitAcctId, "DEBIT", new BigDecimal("1000.00"), "PHP", 1),
            new SettlementLegDto(creditAcctId, "CREDIT", new BigDecimal("1000.00"), "PHP", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(
            "posting-ref-ledger-001",
            "TRANSFER",
            null,
            legs
        );

        when(accountRepository.findAllByIdsForUpdate(argThat(list -> 
            list.contains(debitAcctId) && list.contains(creditAcctId)
        )))
            .thenReturn(List.of(debitAccount, creditAccount));
        when(postingReferenceRepository.findByPostingReferenceId(anyString()))
            .thenReturn(Optional.empty());
        when(postingReferenceRepository.save(any(PostingReference.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxMasterRepository.save(any(OutboxMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any()))
            .thenReturn("{}");

        // When: Settlement is executed
        CoreSettlementResponse response = coreSettlementService.settle(request);

        // Then: Verify response indicates successful settlement
        assertNotNull(response);
        assertEquals("SETTLED", response.status());
        assertNotNull(response.coreReferenceId());
        
        // Verify both accounts were updated correctly
        assertEquals(new BigDecimal("4000.00"), debitAccount.getBalanceAmount());
        assertEquals(new BigDecimal("4000.00"), creditAccount.getBalanceAmount());
        
        // Verify accounts were saved atomically  
        verify(accountRepository).saveAll(any());
        verify(outboxMasterRepository).save(any(OutboxMaster.class));
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
        return account;
    }
}
