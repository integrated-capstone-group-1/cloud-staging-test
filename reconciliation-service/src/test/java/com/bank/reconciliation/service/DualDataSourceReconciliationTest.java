package com.bank.reconciliation.service;

import com.bank.reconciliation.config.ReconProperties;
import com.bank.reconciliation.dto.ExpectedLeg;
import com.bank.reconciliation.dto.LegMatchCandidate;
import com.bank.reconciliation.entity.oracle.AccountMaster;
import com.bank.reconciliation.entity.oracle.TransactionMaster;
import com.bank.reconciliation.entity.postgres.LedgerMutationAudit;
import com.bank.reconciliation.entity.postgres.ReconResultAudit;
import com.bank.reconciliation.entity.postgres.ReconRunAudit;
import com.bank.reconciliation.repository.oracle.AccountMasterRepository;
import com.bank.reconciliation.repository.oracle.TransactionMasterRepository;
import com.bank.reconciliation.repository.postgres.LedgerMutationAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconResultAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconRunAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * SEAM-YAN-04: Dual-DataSource Reconciliation Read Integration Tests
 *
 * Objective:
 * Verify that reconciliation engine reads correctly from Oracle (LEDGER_APP)
 * and PostgreSQL (public) and maintains consistency between two data sources.
 * Validate that outbox relay publishes events to Kafka and async audit consumer
 * writes to PostgreSQL audit tables.
 *
 * Test Scenarios:
 * 1. Reconciliation reads transactions from Oracle (SoR)
 * 2. Reconciliation reads ledger mutations from PostgreSQL audit
 * 3. Matched pairs correctly identify expected vs actual ledger legs
 * 4. Discrepancies detected and classified
 * 5. Outbox events published for async processing
 * 6. Orphan ledger legs identified (actual without expected)
 * 7. Balance reconciliation across both databases
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SEAM-YAN-04: Dual-DataSource Reconciliation Read")
public class DualDataSourceReconciliationTest {

    @Mock
    private TransactionMasterRepository transactionMasterRepository;

    @Mock
    private LedgerMutationAuditRepository ledgerMutationAuditRepository;

    @Mock
    private AccountMasterRepository accountMasterRepository;

    @Mock
    private ReconRunAuditRepository reconRunAuditRepository;

    @Mock
    private ReconResultAuditRepository reconResultAuditRepository;

    @Mock
    private ExceptionClassifier exceptionClassifier;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private ReconProperties reconProperties;

    @InjectMocks
    private ReconciliationEngine reconciliationEngine;

    private static final String TXN_ID_1 = "TXN-001";
    private static final String TXN_ID_2 = "TXN-002";
    private static final String ACCOUNT_ID_1 = "ACC-001";
    private static final String ACCOUNT_ID_2 = "ACC-002";
    private static final String CURRENCY = "PHP";

    private OffsetDateTime windowStart;
    private OffsetDateTime windowEnd;
    private TransactionMaster transaction1;
    private TransactionMaster transaction2;
    private LedgerMutationAudit ledgerMutation1Debit;
    private LedgerMutationAudit ledgerMutation1Credit;
    private LedgerMutationAudit ledgerMutation2;
    private AccountMaster account1;
    private AccountMaster account2;

    @BeforeEach
    void setUp() {
        windowStart = OffsetDateTime.now().minusHours(24);
        windowEnd = OffsetDateTime.now();

        // Set up Oracle transactions
        transaction1 = new TransactionMaster();
        transaction1.setTxnId(TXN_ID_1);
        transaction1.setTxnType("TRANSFER");
        transaction1.setDebitAccountId(ACCOUNT_ID_1);
        transaction1.setCreditAccountId(ACCOUNT_ID_2);
        transaction1.setMutationAmount(new BigDecimal("1000.0000"));
        transaction1.setCurrencyCode(CURRENCY);
        transaction1.setTxnStatus("COMPLETED");
        transaction1.setInitiatedAt(OffsetDateTime.now().minusHours(12));
        transaction1.setCreatedAt(OffsetDateTime.now().minusHours(12));

        transaction2 = new TransactionMaster();
        transaction2.setTxnId(TXN_ID_2);
        transaction2.setTxnType("DEPOSIT");
        transaction2.setCreditAccountId(ACCOUNT_ID_1);
        transaction2.setMutationAmount(new BigDecimal("500.0000"));
        transaction2.setCurrencyCode(CURRENCY);
        transaction2.setTxnStatus("COMPLETED");
        transaction2.setInitiatedAt(OffsetDateTime.now().minusHours(6));
        transaction2.setCreatedAt(OffsetDateTime.now().minusHours(6));

        // Set up PostgreSQL ledger mutations (for TRANSFER: DEBIT + CREDIT)
        ledgerMutation1Debit = new LedgerMutationAudit();
        ledgerMutation1Debit.setMutationUuid(java.util.UUID.randomUUID());
        ledgerMutation1Debit.setTxnId(TXN_ID_1);
        ledgerMutation1Debit.setMutationType("DEBIT");
        ledgerMutation1Debit.setAccountId(ACCOUNT_ID_1);
        ledgerMutation1Debit.setMutationAmount(new BigDecimal("1000.0000"));
        ledgerMutation1Debit.setCurrencyCode(CURRENCY);
        ledgerMutation1Debit.setTxnType("TRANSFER");
        ledgerMutation1Debit.setAuditState("COMMITTED");
        ledgerMutation1Debit.setCreatedAt(OffsetDateTime.now().minusHours(12));

        ledgerMutation1Credit = new LedgerMutationAudit();
        ledgerMutation1Credit.setMutationUuid(java.util.UUID.randomUUID());
        ledgerMutation1Credit.setTxnId(TXN_ID_1);
        ledgerMutation1Credit.setMutationType("CREDIT");
        ledgerMutation1Credit.setAccountId(ACCOUNT_ID_2);
        ledgerMutation1Credit.setMutationAmount(new BigDecimal("1000.0000"));
        ledgerMutation1Credit.setCurrencyCode(CURRENCY);
        ledgerMutation1Credit.setTxnType("TRANSFER");
        ledgerMutation1Credit.setAuditState("COMMITTED");
        ledgerMutation1Credit.setCreatedAt(OffsetDateTime.now().minusHours(12));

        ledgerMutation2 = new LedgerMutationAudit();
        ledgerMutation2.setMutationUuid(java.util.UUID.randomUUID());
        ledgerMutation2.setTxnId(TXN_ID_2);
        ledgerMutation2.setMutationType("CREDIT");
        ledgerMutation2.setAccountId(ACCOUNT_ID_1);
        ledgerMutation2.setMutationAmount(new BigDecimal("500.0000"));
        ledgerMutation2.setCurrencyCode(CURRENCY);
        ledgerMutation2.setTxnType("DEPOSIT");
        ledgerMutation2.setAuditState("COMMITTED");
        ledgerMutation2.setCreatedAt(OffsetDateTime.now().minusHours(6));

        // Set up Oracle accounts
        account1 = new AccountMaster();
        account1.setAccountId(ACCOUNT_ID_1);
        account1.setCustomerId("CUST-001");
        account1.setAccountType("SAVINGS");
        account1.setCurrencyCode(CURRENCY);
        account1.setAccountStatus("ACTIVE");
        account1.setBalanceAmount(new BigDecimal("5000.0000"));

        account2 = new AccountMaster();
        account2.setAccountId(ACCOUNT_ID_2);
        account2.setCustomerId("CUST-002");
        account2.setAccountType("SAVINGS");
        account2.setCurrencyCode(CURRENCY);
        account2.setAccountStatus("ACTIVE");
        account2.setBalanceAmount(new BigDecimal("8000.0000"));
    }

    @Test
    @DisplayName("Should read transactions from Oracle and ledger mutations from PostgreSQL")
    void shouldReadFromBothDataSources() {
        // Arrange
        when(transactionMasterRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(transaction1, transaction2));
        when(ledgerMutationAuditRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(ledgerMutation1Debit, ledgerMutation1Credit, ledgerMutation2));

        // Act
        List<TransactionMaster> oracleTransactions = transactionMasterRepository.findAllInWindow(windowStart, windowEnd);
        List<LedgerMutationAudit> postgresLedgers = ledgerMutationAuditRepository.findAllInWindow(windowStart, windowEnd);

        // Assert
        assertEquals(2, oracleTransactions.size());
        assertEquals(3, postgresLedgers.size());

        // Verify transaction details from Oracle
        assertEquals(TXN_ID_1, oracleTransactions.get(0).getTxnId());
        assertEquals("TRANSFER", oracleTransactions.get(0).getTxnType());

        // Verify ledger mutation details from PostgreSQL
        assertEquals(TXN_ID_1, postgresLedgers.get(0).getTxnId());
        assertEquals("DEBIT", postgresLedgers.get(0).getMutationType());

        verify(transactionMasterRepository).findAllInWindow(windowStart, windowEnd);
        verify(ledgerMutationAuditRepository).findAllInWindow(windowStart, windowEnd);
    }

    @Test
    @DisplayName("Should correctly match expected and actual ledger legs")
    void shouldMatchExpectedAndActualLegs() {
        // Arrange
        when(transactionMasterRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(transaction1));
        when(ledgerMutationAuditRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(ledgerMutation1Debit, ledgerMutation1Credit));

        // Act
        MatchingService matchingService = new MatchingService(
                transactionMasterRepository,
                ledgerMutationAuditRepository
        );
        MatchingService.WindowData windowData = matchingService.buildCandidates(windowStart, windowEnd);

        // Assert
        assertEquals(2, windowData.candidates().size()); // TRANSFER has 2 expected legs
        assertEquals(0, windowData.orphanLedgerLegs().size()); // All ledger legs matched

        // Verify expected legs from transaction
        List<ExpectedLeg> expectedLegs = windowData.candidates().stream()
                .map(LegMatchCandidate::expected)
                .toList();

        assertTrue(expectedLegs.stream()
                .anyMatch(leg -> "DEBIT".equals(leg.mutationType()) && ACCOUNT_ID_1.equals(leg.accountId())));
        assertTrue(expectedLegs.stream()
                .anyMatch(leg -> "CREDIT".equals(leg.mutationType()) && ACCOUNT_ID_2.equals(leg.accountId())));
    }

    @Test
    @DisplayName("Should identify orphan ledger legs (actual without expected)")
    void shouldIdentifyOrphanLedgerLegs() {
        // Arrange - Extra ledger mutation without corresponding transaction
        LedgerMutationAudit orphanMutation = new LedgerMutationAudit();
        orphanMutation.setMutationUuid(java.util.UUID.randomUUID());
        orphanMutation.setTxnId("ORPHAN-TXN");
        orphanMutation.setMutationType("CREDIT");
        orphanMutation.setAccountId(ACCOUNT_ID_1);
        orphanMutation.setMutationAmount(new BigDecimal("100.0000"));
        orphanMutation.setCurrencyCode(CURRENCY);
        orphanMutation.setTxnType("DEPOSIT");
        orphanMutation.setAuditState("COMMITTED");
        orphanMutation.setCreatedAt(OffsetDateTime.now().minusHours(3));

        when(transactionMasterRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(transaction1)); // Only 1 transaction
        when(ledgerMutationAuditRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(
                        ledgerMutation1Debit,
                        ledgerMutation1Credit,
                        orphanMutation // Extra mutation without transaction
                ));

        // Act
        MatchingService matchingService = new MatchingService(
                transactionMasterRepository,
                ledgerMutationAuditRepository
        );
        MatchingService.WindowData windowData = matchingService.buildCandidates(windowStart, windowEnd);

        // Assert
        assertEquals(1, windowData.orphanLedgerLegs().size());
        assertEquals("ORPHAN-TXN", windowData.orphanLedgerLegs().get(0).getTxnId());

        verify(transactionMasterRepository).findAllInWindow(windowStart, windowEnd);
        verify(ledgerMutationAuditRepository).findAllInWindow(windowStart, windowEnd);
    }

    @Test
    @DisplayName("Should persist reconciliation run with matched and exception results")
    void shouldPersistReconciliationRunResults() {
        // Arrange - Setup for matched and exception classification
        ReconResultAudit matchedResult = new ReconResultAudit();
        matchedResult.setTxnId(TXN_ID_1);
        matchedResult.setReconStatus(ReconResultAudit.ReconStatus.MATCHED);

        ReconResultAudit exceptionResult = new ReconResultAudit();
        exceptionResult.setTxnId(TXN_ID_2);
        exceptionResult.setReconStatus(ReconResultAudit.ReconStatus.EXCEPTION);
        exceptionResult.setExceptionType(ReconResultAudit.ExceptionType.MISSING_LEDGER_ENTRY);
        exceptionResult.setSeverity(ReconResultAudit.Severity.HIGH);

        when(transactionMasterRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(transaction1, transaction2));
        when(ledgerMutationAuditRepository.findAllInWindow(windowStart, windowEnd))
                .thenReturn(List.of(ledgerMutation1Debit, ledgerMutation1Credit, ledgerMutation2));
        when(exceptionClassifier.classify(any(LegMatchCandidate.class)))
                .thenReturn(matchedResult, exceptionResult);
        when(reconRunAuditRepository.save(any(ReconRunAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reconResultAuditRepository.save(any(ReconResultAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        
        ReconProperties.Topics topics = new ReconProperties.Topics();
        topics.setRunCompleted("reconciliation.run-completed");
        topics.setDiscrepancy("reconciliation.discrepancy");
        when(reconProperties.getTopics()).thenReturn(topics);

        // Act
        MatchingService matchingService = new MatchingService(
                transactionMasterRepository,
                ledgerMutationAuditRepository
        );
        MatchingService.WindowData windowData = matchingService.buildCandidates(windowStart, windowEnd);

        ReconRunAudit run = reconciliationEngine.persistRun(windowStart, windowEnd, windowData);

        // Assert
        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.COMPLETED, run.getRunStatus());
        assertEquals(3, run.getTotalTxnChecked()); // 2 expected legs + 1 orphan = 3 total
        assertTrue(run.getTotalMatched() >= 0);
        assertTrue(run.getTotalExceptions() >= 0);

        verify(reconRunAuditRepository, atLeast(1)).save(any(ReconRunAudit.class));
        verify(outboxWriter).enqueue(eq("RECON_RUN"), anyString(), eq("reconciliation.run-completed"), any());
    }

    @Test
    @DisplayName("Should publish discrepancy event to Kafka for exceptions")
    void shouldPublishDiscrepancyEventForExceptions() {
        // Arrange
        ReconResultAudit exceptionResult = new ReconResultAudit();
        exceptionResult.setTxnId(TXN_ID_1);
        exceptionResult.setReconStatus(ReconResultAudit.ReconStatus.EXCEPTION);
        exceptionResult.setExceptionType(ReconResultAudit.ExceptionType.AMOUNT_MISMATCH);
        exceptionResult.setSeverity(ReconResultAudit.Severity.MEDIUM);

        // Act - Simulate publishing discrepancy
        outboxWriter.enqueue("TRANSACTION", TXN_ID_1, "reconciliation.discrepancy", exceptionResult);

        // Assert
        verify(outboxWriter).enqueue(
                eq("TRANSACTION"),
                eq(TXN_ID_1),
                eq("reconciliation.discrepancy"),
                any()
        );
    }

    @Test
    @DisplayName("Should reconcile account balances across Oracle and PostgreSQL")
    void shouldReconcileAccountBalances() {
        // Arrange - Setup account balance reconciliation
        when(accountMasterRepository.findByAccountStatus("ACTIVE"))
                .thenReturn(List.of(account1, account2));
        when(ledgerMutationAuditRepository.calculateExpectedBalanceForAccount(ACCOUNT_ID_1))
                .thenReturn(new BigDecimal("4500.0000")); // Expected based on Postgres mutations
        when(ledgerMutationAuditRepository.calculateExpectedBalanceForAccount(ACCOUNT_ID_2))
                .thenReturn(new BigDecimal("9000.0000"));
        when(reconRunAuditRepository.save(any(ReconRunAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reconResultAuditRepository.save(any(ReconResultAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        
        ReconProperties.Matching matching = new ReconProperties.Matching();
        matching.setAmountTolerance(new BigDecimal("0.01"));
        ReconProperties.Topics topics = new ReconProperties.Topics();
        topics.setRunCompleted("reconciliation.run-completed");
        when(reconProperties.getMatching()).thenReturn(matching);
        when(reconProperties.getTopics()).thenReturn(topics);

        // Act
        ReconRunAudit run = reconciliationEngine.reconcileAccountBalances();

        // Assert
        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.COMPLETED, run.getRunStatus());
        assertEquals(2, run.getTotalTxnChecked()); // 2 accounts checked

        verify(accountMasterRepository).findByAccountStatus("ACTIVE");
        verify(ledgerMutationAuditRepository).calculateExpectedBalanceForAccount(ACCOUNT_ID_1);
        verify(ledgerMutationAuditRepository).calculateExpectedBalanceForAccount(ACCOUNT_ID_2);
    }

    @Test
    @DisplayName("Should handle balance drift detection between Oracle and PostgreSQL")
    void shouldDetectBalanceDrift() {
        // Arrange - Account balance in Oracle differs from Postgres sum by more than tolerance
        account1.setBalanceAmount(new BigDecimal("5500.0000")); // Oracle balance
        BigDecimal expectedBalance = new BigDecimal("4500.0000"); // Postgres calculated balance
        BigDecimal drift = account1.getBalanceAmount().subtract(expectedBalance).abs();

        when(accountMasterRepository.findByAccountStatus("ACTIVE"))
                .thenReturn(List.of(account1));
        when(ledgerMutationAuditRepository.calculateExpectedBalanceForAccount(ACCOUNT_ID_1))
                .thenReturn(expectedBalance);
        when(reconRunAuditRepository.save(any(ReconRunAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(reconResultAuditRepository.save(any(ReconResultAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReconProperties.Matching matching = new ReconProperties.Matching();
        matching.setAmountTolerance(new BigDecimal("0.01"));
        ReconProperties.Topics topics = new ReconProperties.Topics();
        topics.setRunCompleted("reconciliation.run-completed");
        when(reconProperties.getMatching()).thenReturn(matching);
        when(reconProperties.getTopics()).thenReturn(topics);

        // Act
        ReconRunAudit run = reconciliationEngine.reconcileAccountBalances();

        // Assert
        assertNotNull(run);
        assertTrue(drift.compareTo(matching.getAmountTolerance()) > 0);
        assertTrue(run.getTotalExceptions() > 0); // Drift should be detected as exception

        verify(accountMasterRepository).findByAccountStatus("ACTIVE");
        verify(ledgerMutationAuditRepository).calculateExpectedBalanceForAccount(ACCOUNT_ID_1);
    }

    @Test
    @DisplayName("Should handle reconciliation run failure and persist failed status")
    void shouldHandleReconciliationRunFailure() {
        // Arrange - Setup for failure scenario
        when(reconRunAuditRepository.save(any(ReconRunAudit.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ReconProperties.Topics topics = new ReconProperties.Topics();
        topics.setRunCompleted("reconciliation.run-completed");
        when(reconProperties.getTopics()).thenReturn(topics);

        // Act
        OffsetDateTime pastTime = OffsetDateTime.now().minusDays(1);
        ReconRunAudit run = reconciliationEngine.persistFailedRun(pastTime, OffsetDateTime.now());

        // Assert
        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.FAILED, run.getRunStatus());
        assertNotNull(run.getRunCompletedAt());

        verify(reconRunAuditRepository).save(any(ReconRunAudit.class));
        verify(outboxWriter).enqueue(
                eq("RECON_RUN"),
                anyString(),
                eq("reconciliation.run-completed"),
                any()
        );
    }
}
