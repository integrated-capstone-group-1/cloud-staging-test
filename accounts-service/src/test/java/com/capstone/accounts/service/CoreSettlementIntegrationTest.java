package com.capstone.accounts.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountHoldRepository;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.LedgerEntryRepository;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.accounts.repository.PostingReferenceRepository;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.SettlementLegDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Integration Test Suite for CBS Core Settlement Row-Level Locking
 *
 * Tests that concurrent settlements properly use row-level locks (SELECT ... FOR UPDATE)
 * and acquire locks in ascending account ID order to prevent deadlocks.
 *
 * This test simulates concurrent behavior using mocks and verifies that the service
 * applies locks in deterministic order (ascending account IDs) to prevent deadlocks.
 *
 * Related Issues: DIG-52
 * Related BRs: BR-01 (Zero Overdraft), TS-01 (Concurrency)
 */
@DisplayName("CoreSettlementService - Row-Level Locking & Deadlock Prevention (Concurrent Simulation)")
class CoreSettlementIntegrationTest {

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

    @Captor
    private ArgumentCaptor<List<String>> lockOrderCaptor;

    private CoreSettlementService coreSettlementService;
    private Map<String, AccountMaster> accountStore;
    private List<List<String>> capturedLockOrders;

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
        
        accountStore = new ConcurrentHashMap<>();
        capturedLockOrders = Collections.synchronizedList(new ArrayList<>());
        
        // Mock the findAllByIdsForUpdate to capture lock ordering
        when(accountRepository.findAllByIdsForUpdate(any()))
            .thenAnswer(invocation -> {
                List<String> requestedIds = invocation.getArgument(0);
                capturedLockOrders.add(new ArrayList<>(requestedIds));
                return requestedIds.stream()
                    .map(accountStore::get)
                    .collect(Collectors.toList());
            });
        
        when(postingReferenceRepository.findByPostingReferenceId(any()))
            .thenReturn(Optional.empty());
        when(postingReferenceRepository.save(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxMasterRepository.save(any()))
            .thenAnswer(invocation -> invocation.getArgument(0));
        try {
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        } catch (JsonProcessingException e) {
            // Not possible in this mock context
        }
        when(accountRepository.saveAll(any()))
            .thenAnswer(invocation -> {
                Iterable<AccountMaster> accounts = invocation.getArgument(0);
                List<AccountMaster> list = new ArrayList<>();
                accounts.forEach(list::add);
                return list;
            });
    }

    // ==========================================
    // SEAM-YAN-01: Row-Level Locking Integration
    // ==========================================

    @Test
    @DisplayName("SEAM-YAN-01: Should request locks in ascending account ID order")
    void shouldRequestLocksInAscendingAccountIdOrder() throws Exception {
        // Given: Two settlements with accounts in different orderings
        String acctA = "acct-a-001";
        String acctB = "acct-b-001";
        
        AccountMaster accA = createTestAccount(acctA, new BigDecimal("10000.00"));
        AccountMaster accB = createTestAccount(acctB, new BigDecimal("5000.00"));
        accountStore.put(acctA, accA);
        accountStore.put(acctB, accB);

        // When: Settlement 1 requests locks for [acctB, acctA] (unsorted input)
        List<SettlementLegDto> legs1 = List.of(
            new SettlementLegDto(acctB, "DEBIT", new BigDecimal("100.00"), "PHP", 1),
            new SettlementLegDto(acctA, "CREDIT", new BigDecimal("100.00"), "PHP", 2)
        );
        
        CoreSettlementRequest request1 = new CoreSettlementRequest(
            "posting-ref-1",
            "TRANSFER",
            null,
            legs1
        );
        
        coreSettlementService.settle(request1);

        // Then: Verify locks were requested in ascending order [acctA, acctB]
        verify(accountRepository).findAllByIdsForUpdate(any());
        assertTrue(capturedLockOrders.size() >= 1, "Lock order should be captured");
        List<String> lockOrder = capturedLockOrders.get(0);
        assertEquals(List.of(acctA, acctB), lockOrder, 
            "Locks should be acquired in ascending order [acctA, acctB]");
    }

    @Test
    @DisplayName("SEAM-YAN-01: Should handle concurrent settlements without interleaving")
    void shouldHandleConcurrentSettlementsWithDeterministicLocking() throws Exception {
        // Given: Two concurrent settlements
        String acctA = "acct-a-002";
        String acctB = "acct-b-002";
        
        AccountMaster accA = createTestAccount(acctA, new BigDecimal("10000.00"));
        AccountMaster accB = createTestAccount(acctB, new BigDecimal("5000.00"));
        accountStore.put(acctA, accA);
        accountStore.put(acctB, accB);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        try {
            // Thread 1: Settlement with legs [acctA, acctB]
            executor.submit(() -> {
                try {
                    startSignal.await();
                    
                    List<SettlementLegDto> legs = List.of(
                        new SettlementLegDto(acctA, "DEBIT", new BigDecimal("100.00"), "PHP", 1),
                        new SettlementLegDto(acctB, "CREDIT", new BigDecimal("100.00"), "PHP", 2)
                    );
                    
                    CoreSettlementRequest request = new CoreSettlementRequest(
                        "posting-ref-thread-1",
                        "TRANSFER",
                        null,
                        legs
                    );
                    
                    coreSettlementService.settle(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneSignal.countDown();
                }
            });

            // Thread 2: Settlement with legs [acctB, acctA] (reversed order)
            executor.submit(() -> {
                try {
                    startSignal.await();
                    
                    List<SettlementLegDto> legs = List.of(
                        new SettlementLegDto(acctB, "DEBIT", new BigDecimal("50.00"), "PHP", 1),
                        new SettlementLegDto(acctA, "CREDIT", new BigDecimal("50.00"), "PHP", 2)
                    );
                    
                    CoreSettlementRequest request = new CoreSettlementRequest(
                        "posting-ref-thread-2",
                        "TRANSFER",
                        null,
                        legs
                    );
                    
                    coreSettlementService.settle(request);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneSignal.countDown();
                }
            });

            startSignal.countDown();
            boolean completed = doneSignal.await(10, TimeUnit.SECONDS);

            // Then: Both should complete successfully
            assertTrue(completed, "Settlements should complete without blocking");
            assertEquals(2, successCount.get(), "Both settlements should succeed");
            
            // Verify both used deterministic lock ordering
            assertEquals(2, capturedLockOrders.size(), "Should have 2 lock requests");
            for (List<String> order : capturedLockOrders) {
                assertEquals(List.of(acctA, acctB), order,
                    "All lock requests should use ascending order [acctA, acctB]");
            }

        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("SEAM-YAN-01: Should verify lock ordering prevents deadlocks")
    void shouldVerifyLockOrderingPreventsDeadlocks() throws Exception {
        // Given: Multiple account pairs with different ordering patterns
        String acct1 = "acct-1";
        String acct2 = "acct-2";
        String acct3 = "acct-3";
        
        accountStore.put(acct1, createTestAccount(acct1, new BigDecimal("10000.00")));
        accountStore.put(acct2, createTestAccount(acct2, new BigDecimal("10000.00")));
        accountStore.put(acct3, createTestAccount(acct3, new BigDecimal("10000.00")));

        // When: Multiple settlements with different leg orderings
        List<CoreSettlementRequest> requests = List.of(
            new CoreSettlementRequest("posting-ref-A", "TRANSFER", null, List.of(
                new SettlementLegDto(acct3, "DEBIT", new BigDecimal("100.00"), "PHP", 1),
                new SettlementLegDto(acct1, "CREDIT", new BigDecimal("100.00"), "PHP", 2),
                new SettlementLegDto(acct2, "CREDIT", new BigDecimal("100.00"), "PHP", 3)
            )),
            new CoreSettlementRequest("posting-ref-B", "TRANSFER", null, List.of(
                new SettlementLegDto(acct2, "DEBIT", new BigDecimal("50.00"), "PHP", 1),
                new SettlementLegDto(acct3, "CREDIT", new BigDecimal("25.00"), "PHP", 2),
                new SettlementLegDto(acct1, "CREDIT", new BigDecimal("25.00"), "PHP", 3)
            ))
        );
        
        for (CoreSettlementRequest request : requests) {
            coreSettlementService.settle(request);
        }

        // Then: All settlements should have used consistent ascending order
        assertEquals(2, capturedLockOrders.size(), "Should have 2 settlement lock requests");
        
        // Expected order for all settlements: [acct1, acct2, acct3]
        List<String> expectedOrder = List.of(acct1, acct2, acct3);
        for (List<String> actualOrder : capturedLockOrders) {
            assertEquals(expectedOrder, actualOrder,
                "All settlements should use the same deterministic ascending lock order");
        }
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
        account.setCustomerId("cust-test-001");
        account.setIsPrimary("Y");
        return account;
    }
}
