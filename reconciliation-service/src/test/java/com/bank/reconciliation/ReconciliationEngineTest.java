package com.bank.reconciliation;

import com.bank.reconciliation.config.ReconProperties;
import com.bank.reconciliation.dto.ExpectedLeg;
import com.bank.reconciliation.dto.LegMatchCandidate;
import com.bank.reconciliation.entity.oracle.AccountMaster;
import com.bank.reconciliation.entity.oracle.TransactionMaster;
import com.bank.reconciliation.entity.postgres.LedgerMutationAudit;
import com.bank.reconciliation.entity.postgres.ReconResultAudit;
import com.bank.reconciliation.entity.postgres.ReconRunAudit;
import com.bank.reconciliation.event.ReconciliationDiscrepancyEvent;
import com.bank.reconciliation.repository.oracle.AccountMasterRepository;
import com.bank.reconciliation.repository.postgres.LedgerMutationAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconResultAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconRunAuditRepository;
import com.bank.reconciliation.service.ExceptionClassifier;
import com.bank.reconciliation.service.MatchingService;
import com.bank.reconciliation.service.OutboxWriter;
import com.bank.reconciliation.service.ReconciliationEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReconciliationEngineTest {

    @Mock
    private MatchingService matchingService;

    @Mock
    private ExceptionClassifier exceptionClassifier;

    @Mock
    private ReconRunAuditRepository reconRunAuditRepository;

    @Mock
    private ReconResultAuditRepository reconResultAuditRepository;

    @Mock
    private OutboxWriter outboxWriter;

    @Mock
    private AccountMasterRepository accountMasterRepository;

    @Mock
    private LedgerMutationAuditRepository ledgerMutationAuditRepository;

    private ReconProperties props;
    private ReconciliationEngine reconciliationEngine;

    @BeforeEach
    void setUp() {
        props = new ReconProperties();
        props.getMatching().setAmountTolerance(BigDecimal.ZERO);
        props.getTopics().setDiscrepancy("reconciliation.discrepancy");
        props.getTopics().setRunCompleted("reconciliation.run.completed");

        reconciliationEngine = new ReconciliationEngine(
                matchingService,
                exceptionClassifier,
                reconRunAuditRepository,
                reconResultAuditRepository,
                outboxWriter,
                props,
                accountMasterRepository,
                ledgerMutationAuditRepository
        );
    }

    @Test
    @DisplayName("T-012 Done-When 1: Balanced account results in MATCHED status with zero exceptions")
    void testReconcileAccountBalances_whenBalanced_resultsInMatchedStatusWithZeroExceptions() {
        AccountMaster account = new AccountMaster();
        account.setAccountId("ACC-PHP-1001");
        account.setAccountType("SAVINGS");
        account.setCurrencyCode("PHP");
        account.setAccountStatus("ACTIVE");
        account.setBalanceAmount(new BigDecimal("1500.0000"));

        when(accountMasterRepository.findByAccountStatus("ACTIVE")).thenReturn(List.of(account));
        when(ledgerMutationAuditRepository.calculateExpectedBalanceForAccount("ACC-PHP-1001"))
                .thenReturn(new BigDecimal("1500.0000"));

        ReconRunAudit run = reconciliationEngine.reconcileAccountBalances();

        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.COMPLETED, run.getRunStatus());
        assertEquals(1, run.getTotalTxnChecked());
        assertEquals(1, run.getTotalMatched());
        assertEquals(0, run.getTotalExceptions());

        ArgumentCaptor<ReconResultAudit> resultCaptor = ArgumentCaptor.forClass(ReconResultAudit.class);
        verify(reconResultAuditRepository).save(resultCaptor.capture());
        ReconResultAudit savedResult = resultCaptor.getValue();

        assertEquals(ReconResultAudit.ReconStatus.MATCHED, savedResult.getReconStatus());
        assertNull(savedResult.getExceptionType());
        assertEquals("ACC-PHP-1001", savedResult.getAccountId());
        assertEquals(new BigDecimal("1500.0000"), savedResult.getActualAmount());
        assertEquals(new BigDecimal("1500.0000"), savedResult.getExpectedAmount());

        // Verify NO discrepancy event was emitted, but run completed was emitted
        verify(outboxWriter, never()).enqueue(eq("TRANSACTION"), anyString(), eq("reconciliation.discrepancy"), any());
        verify(outboxWriter).enqueue(eq("RECON_RUN"), anyString(), eq("reconciliation.run.completed"), any());
    }

    @Test
    @DisplayName("T-012 Done-When 2: Injected balance discrepancy correctly creates recon_result_audit record and publishes Kafka discrepancy event")
    void testReconcileAccountBalances_whenDiscrepancy_createsReconResultAuditAndPublishesKafkaDiscrepancyEvent() {
        AccountMaster account = new AccountMaster();
        account.setAccountId("ACC-PHP-2002");
        account.setAccountType("SAVINGS");
        account.setCurrencyCode("PHP");
        account.setAccountStatus("ACTIVE");
        account.setBalanceAmount(new BigDecimal("2000.0000")); // Live Oracle balance

        when(accountMasterRepository.findByAccountStatus("ACTIVE")).thenReturn(List.of(account));
        // Postgres expected sum is 1800.0000 (Drift of 200.0000)
        when(ledgerMutationAuditRepository.calculateExpectedBalanceForAccount("ACC-PHP-2002"))
                .thenReturn(new BigDecimal("1800.0000"));

        ReconRunAudit run = reconciliationEngine.reconcileAccountBalances();

        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.COMPLETED, run.getRunStatus());
        assertEquals(1, run.getTotalTxnChecked());
        assertEquals(0, run.getTotalMatched());
        assertEquals(1, run.getTotalExceptions());

        ArgumentCaptor<ReconResultAudit> resultCaptor = ArgumentCaptor.forClass(ReconResultAudit.class);
        verify(reconResultAuditRepository).save(resultCaptor.capture());
        ReconResultAudit savedResult = resultCaptor.getValue();

        assertEquals(ReconResultAudit.ReconStatus.EXCEPTION, savedResult.getReconStatus());
        assertEquals(ReconResultAudit.ExceptionType.BALANCE_DRIFT, savedResult.getExceptionType());
        assertEquals(ReconResultAudit.Severity.HIGH, savedResult.getSeverity());
        assertEquals("ACC-PHP-2002", savedResult.getAccountId());
        assertEquals(new BigDecimal("2000.0000"), savedResult.getActualAmount());
        assertEquals(new BigDecimal("1800.0000"), savedResult.getExpectedAmount());

        // Verify high-priority discrepancy event WAS published to outbox
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(outboxWriter).enqueue(
                eq("TRANSACTION"),
                eq("BAL-ACC-PHP-2002"),
                eq("reconciliation.discrepancy"),
                eventCaptor.capture()
        );

        assertTrue(eventCaptor.getValue() instanceof ReconciliationDiscrepancyEvent);
        ReconciliationDiscrepancyEvent event = (ReconciliationDiscrepancyEvent) eventCaptor.getValue();
        assertEquals("BAL-ACC-PHP-2002", event.txnId());
        assertEquals("ACC-PHP-2002", event.accountId());
        assertEquals("BALANCE_DRIFT", event.exceptionType());
        assertEquals(new BigDecimal("1800.0000"), event.expectedAmount());
        assertEquals(new BigDecimal("2000.0000"), event.actualAmount());
    }

    @Test
    @DisplayName("Transaction leg recon: Balanced candidates result in MATCHED status")
    void testRun_whenLegsBalanced_resultsInMatchedWithZeroExceptions() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        OffsetDateTime end = OffsetDateTime.now();

        TransactionMaster txn = new TransactionMaster();
        txn.setTxnId("TXN-3001");
        txn.setDebitAccountId("ACC-1");
        txn.setTxnType("WITHDRAWAL");
        txn.setCurrencyCode("PHP");
        txn.setMutationAmount(new BigDecimal("100.0000"));
        txn.setTxnStatus("COMMITTED");

        ExpectedLeg expected = ExpectedLeg.from(txn).get(0);
        LegMatchCandidate candidate = new LegMatchCandidate(expected, List.of());

        ReconResultAudit matchedResult = new ReconResultAudit();
        matchedResult.setTxnId("TXN-3001");
        matchedResult.setReconStatus(ReconResultAudit.ReconStatus.MATCHED);

        when(matchingService.buildCandidates(start, end))
                .thenReturn(new MatchingService.WindowData(List.of(candidate), List.of()));
        when(exceptionClassifier.classify(candidate)).thenReturn(matchedResult);

        ReconRunAudit run = reconciliationEngine.run(start, end);

        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.COMPLETED, run.getRunStatus());
        assertEquals(1, run.getTotalTxnChecked());
        assertEquals(1, run.getTotalMatched());
        assertEquals(0, run.getTotalExceptions());

        verify(outboxWriter, never()).enqueue(eq("TRANSACTION"), anyString(), eq("reconciliation.discrepancy"), any());
        verify(outboxWriter).enqueue(eq("RECON_RUN"), anyString(), eq("reconciliation.run.completed"), any());
    }

    @Test
    @DisplayName("Transaction leg recon: Mismatch candidate triggers EXCEPTION and publishes discrepancy")
    void testRun_whenCandidateMismatch_createsExceptionAndPublishesDiscrepancy() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        OffsetDateTime end = OffsetDateTime.now();

        TransactionMaster txn = new TransactionMaster();
        txn.setTxnId("TXN-4001");
        txn.setDebitAccountId("ACC-1");
        txn.setTxnType("WITHDRAWAL");
        txn.setCurrencyCode("PHP");
        txn.setMutationAmount(new BigDecimal("100.0000"));
        txn.setTxnStatus("COMMITTED");

        ExpectedLeg expected = ExpectedLeg.from(txn).get(0);
        LegMatchCandidate candidate = new LegMatchCandidate(expected, List.of());

        ReconResultAudit exceptionResult = new ReconResultAudit();
        exceptionResult.setResultId(UUID.randomUUID());
        exceptionResult.setTxnId("TXN-4001");
        exceptionResult.setAccountId("ACC-1");
        exceptionResult.setReconStatus(ReconResultAudit.ReconStatus.EXCEPTION);
        exceptionResult.setExceptionType(ReconResultAudit.ExceptionType.AMOUNT_MISMATCH);
        exceptionResult.setSeverity(ReconResultAudit.Severity.HIGH);
        exceptionResult.setExpectedAmount(new BigDecimal("100.0000"));
        exceptionResult.setActualAmount(new BigDecimal("90.0000"));

        when(matchingService.buildCandidates(start, end))
                .thenReturn(new MatchingService.WindowData(List.of(candidate), List.of()));
        when(exceptionClassifier.classify(candidate)).thenReturn(exceptionResult);

        ReconRunAudit run = reconciliationEngine.run(start, end);

        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.COMPLETED, run.getRunStatus());
        assertEquals(1, run.getTotalTxnChecked());
        assertEquals(0, run.getTotalMatched());
        assertEquals(1, run.getTotalExceptions());

        verify(outboxWriter).enqueue(
                eq("TRANSACTION"),
                eq("TXN-4001"),
                eq("reconciliation.discrepancy"),
                any(ReconciliationDiscrepancyEvent.class)
        );
    }

    @Test
    @DisplayName("Transaction leg recon: Orphan ledger leg triggers EXCEPTION and publishes discrepancy")
    void testRun_whenOrphanLedgerLeg_createsExceptionAndPublishesDiscrepancy() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        OffsetDateTime end = OffsetDateTime.now();

        LedgerMutationAudit orphanLeg = new LedgerMutationAudit();
        orphanLeg.setMutationUuid(UUID.randomUUID());
        orphanLeg.setTxnId("TXN-ORPHAN");
        orphanLeg.setAccountId("ACC-2");
        orphanLeg.setMutationAmount(new BigDecimal("50.0000"));

        ReconResultAudit exceptionResult = new ReconResultAudit();
        exceptionResult.setResultId(UUID.randomUUID());
        exceptionResult.setTxnId("TXN-ORPHAN");
        exceptionResult.setAccountId("ACC-2");
        exceptionResult.setReconStatus(ReconResultAudit.ReconStatus.EXCEPTION);
        exceptionResult.setExceptionType(ReconResultAudit.ExceptionType.ORPHAN_LEDGER_ENTRY);
        exceptionResult.setSeverity(ReconResultAudit.Severity.CRITICAL);

        when(matchingService.buildCandidates(start, end))
                .thenReturn(new MatchingService.WindowData(List.of(), List.of(orphanLeg)));
        when(exceptionClassifier.classifyOrphan(orphanLeg)).thenReturn(exceptionResult);

        ReconRunAudit run = reconciliationEngine.run(start, end);

        assertNotNull(run);
        assertEquals(1, run.getTotalExceptions());
        verify(outboxWriter).enqueue(eq("TRANSACTION"), eq("TXN-ORPHAN"), eq("reconciliation.discrepancy"), any());
    }

    @Test
    @DisplayName("Reconciliation run handles exception and sets status to FAILED")
    void testRun_whenExceptionThrown_persistsFailedRun() {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1);
        OffsetDateTime end = OffsetDateTime.now();

        when(matchingService.buildCandidates(start, end)).thenThrow(new RuntimeException("Database error"));

        ReconRunAudit run = reconciliationEngine.run(start, end);

        assertNotNull(run);
        assertEquals(ReconRunAudit.Status.FAILED, run.getRunStatus());
        verify(outboxWriter).enqueue(eq("RECON_RUN"), anyString(), eq("reconciliation.run.completed"), any());
    }
}

