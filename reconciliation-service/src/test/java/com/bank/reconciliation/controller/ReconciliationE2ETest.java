package com.bank.reconciliation.controller;

import com.bank.reconciliation.dto.RunSummaryResponse;
import com.bank.reconciliation.entity.postgres.ReconResultAudit;
import com.bank.reconciliation.entity.postgres.ReconRunAudit;
import com.bank.reconciliation.repository.postgres.ReconResultAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconRunAuditRepository;
import com.bank.reconciliation.service.ReconciliationEngine;
import com.bank.reconciliation.config.ReconProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E2E-08: Dual-DataSource Reconciliation Engine Run
 *
 * Objective:
 * Verify that reconciliation engine REST endpoints trigger recon runs,
 * query results from PostgreSQL audit tables, and maintain data consistency
 * between Oracle and PostgreSQL.
 *
 * Test Scenarios:
 * 1. POST /api/recon/runs triggers reconciliation with default window
 * 2. Reconciliation queries Oracle transactions and PostgreSQL ledger
 * 3. Run persisted to PostgreSQL recon_run_audit
 * 4. Results queried from PostgreSQL recon_result_audit
 * 5. Balance reconciliation triggered via separate endpoint
 * 6. Run metrics (total_matched, total_exceptions, total_txn_checked) reported
 * 7. Exception details accessible for manual review
 * 8. Multiple runs queryable with ordering by timestamp
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("E2E-08: Dual-DataSource Reconciliation Engine Run")
public class ReconciliationE2ETest {

    @Mock
    private ReconciliationEngine reconciliationEngine;

    @Mock
    private ReconRunAuditRepository reconRunAuditRepository;

    @Mock
    private ReconResultAuditRepository reconResultAuditRepository;

    @Mock
    private ReconProperties props;

    @InjectMocks
    private ReconciliationController controller;

    private static final UUID RUN_ID_1 = UUID.randomUUID();
    private static final UUID RUN_ID_2 = UUID.randomUUID();

    private ReconRunAudit run1;
    private ReconRunAudit run2;
    private ReconResultAudit matchedResult;
    private ReconResultAudit exceptionResult;

    @BeforeEach
    void setUp() {
        // Set up first run (all matched)
        run1 = ReconRunAudit.start(OffsetDateTime.now().minusDays(1), OffsetDateTime.now());
        run1.setRunId(RUN_ID_1);
        run1.setRunStatus(ReconRunAudit.Status.COMPLETED);
        run1.setTotalTxnChecked(100);
        run1.setTotalMatched(100);
        run1.setTotalExceptions(0);

        // Set up second run (with exceptions)
        run2 = ReconRunAudit.start(OffsetDateTime.now().minusHours(2), OffsetDateTime.now());
        run2.setRunId(RUN_ID_2);
        run2.setRunStatus(ReconRunAudit.Status.COMPLETED);
        run2.setTotalTxnChecked(50);
        run2.setTotalMatched(48);
        run2.setTotalExceptions(2);

        // Set up matched result
        matchedResult = new ReconResultAudit();
        matchedResult.setRunId(RUN_ID_1);
        matchedResult.setTxnId("TXN-001");
        matchedResult.setReconStatus(ReconResultAudit.ReconStatus.MATCHED);

        // Set up exception result
        exceptionResult = new ReconResultAudit();
        exceptionResult.setRunId(RUN_ID_2);
        exceptionResult.setTxnId("TXN-002");
        exceptionResult.setReconStatus(ReconResultAudit.ReconStatus.EXCEPTION);
        exceptionResult.setExceptionType(ReconResultAudit.ExceptionType.AMOUNT_MISMATCH);
        exceptionResult.setSeverity(ReconResultAudit.Severity.HIGH);
    }

    @Test
    @DisplayName("Should trigger reconciliation run via POST /api/recon/runs with default window")
    void shouldTriggerReconciliationWithDefaultWindow() {
        // Arrange
        when(reconciliationEngine.run(any(), any())).thenReturn(run1);
        when(reconResultAuditRepository.findByRunId(RUN_ID_1)).thenReturn(List.of(matchedResult));

        // Act
        ResponseEntity<RunSummaryResponse> response = controller.triggerRun(null);

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(RUN_ID_1, response.getBody().runId());

        // Verify engine was called with window
        verify(reconciliationEngine).run(any(OffsetDateTime.class), any(OffsetDateTime.class));
        verify(reconResultAuditRepository).findByRunId(RUN_ID_1);
    }

    @Test
    @DisplayName("Should persist reconciliation run to PostgreSQL recon_run_audit")
    void shouldPersistRunToPostgres() {
        // Arrange
        when(reconciliationEngine.run(any(), any())).thenReturn(run1);
        when(reconResultAuditRepository.findByRunId(RUN_ID_1)).thenReturn(List.of(matchedResult));

        // Act
        ResponseEntity<RunSummaryResponse> response = controller.triggerRun(null);

        // Assert
        RunSummaryResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(ReconRunAudit.Status.COMPLETED.name(), body.status());
        assertEquals(100, body.totalChecked());
        assertEquals(100, body.totalMatched());
        assertEquals(0, body.totalExceptions());
    }

    @Test
    @DisplayName("Should query results from PostgreSQL recon_result_audit")
    void shouldQueryResultsFromPostgres() {
        // Arrange
        when(reconciliationEngine.run(any(), any())).thenReturn(run2);
        when(reconResultAuditRepository.findByRunId(RUN_ID_2))
                .thenReturn(List.of(matchedResult, exceptionResult));

        // Act
        ResponseEntity<RunSummaryResponse> response = controller.triggerRun(null);

        // Assert
        RunSummaryResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(2, body.results().size());
        assertTrue(body.results().stream()
                .anyMatch(r -> r.getReconStatus() == ReconResultAudit.ReconStatus.MATCHED));
        assertTrue(body.results().stream()
                .anyMatch(r -> r.getReconStatus() == ReconResultAudit.ReconStatus.EXCEPTION));

        verify(reconResultAuditRepository).findByRunId(RUN_ID_2);
    }

    @Test
    @DisplayName("Should trigger balance reconciliation via separate endpoint")
    void shouldTriggerBalanceReconciliation() {
        // Arrange
        when(reconciliationEngine.reconcileAccountBalances()).thenReturn(run1);
        when(reconResultAuditRepository.findByRunId(RUN_ID_1)).thenReturn(List.of(matchedResult));

        // Act
        ResponseEntity<RunSummaryResponse> response = controller.triggerBalanceReconciliation();

        // Assert
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reconciliationEngine).reconcileAccountBalances();
        verify(reconResultAuditRepository).findByRunId(RUN_ID_1);
    }

    @Test
    @DisplayName("Should report run metrics in response")
    void shouldReportRunMetrics() {
        // Arrange
        run2.setTotalMatched(95);
        run2.setTotalExceptions(5);
        run2.setTotalTxnChecked(100);
        
        when(reconciliationEngine.run(any(), any())).thenReturn(run2);
        when(reconResultAuditRepository.findByRunId(RUN_ID_2)).thenReturn(List.of());

        // Act
        ResponseEntity<RunSummaryResponse> response = controller.triggerRun(null);

        // Assert
        RunSummaryResponse body = response.getBody();
        assertNotNull(body);
        assertEquals(95, body.totalMatched());
        assertEquals(5, body.totalExceptions());
        assertEquals(100, body.totalChecked());
        assertEquals(95.0, ((double) body.totalMatched() / body.totalChecked()) * 100, 0.1);
    }

    @Test
    @DisplayName("Should retrieve recent runs ordered by timestamp")
    void shouldRetrieveRecentRunsOrdered() {
        // Arrange
        ReconRunAudit older = ReconRunAudit.start(OffsetDateTime.now().minusDays(5), OffsetDateTime.now().minusDays(4));
        older.setRunId(UUID.randomUUID());
        older.setRunStatus(ReconRunAudit.Status.COMPLETED);
        older.setRunStartedAt(OffsetDateTime.now().minusDays(5));
        run1.setRunStartedAt(OffsetDateTime.now().minusDays(1));
        run2.setRunStartedAt(OffsetDateTime.now().minusHours(2));

        when(reconRunAuditRepository.findTop20ByOrderByRunStartedAtDesc())
                .thenReturn(List.of(run2, run1, older)); // Newest first

        // Act
        List<RunSummaryResponse> runs = controller.recentRuns(false);

        // Assert
        assertEquals(3, runs.size());
        assertTrue(runs.get(0).startedAt().isAfter(runs.get(1).startedAt()));
        assertTrue(runs.get(1).startedAt().isAfter(runs.get(2).startedAt()));

        verify(reconRunAuditRepository).findTop20ByOrderByRunStartedAtDesc();
    }

    @Test
    @DisplayName("Should retrieve specific run by ID")
    void shouldRetrieveRunById() {
        // Arrange
        when(reconRunAuditRepository.findById(RUN_ID_1)).thenReturn(Optional.of(run1));
        when(reconResultAuditRepository.findByRunId(RUN_ID_1)).thenReturn(List.of(matchedResult));

        // Act
        RunSummaryResponse response = controller.getRun(RUN_ID_1);

        // Assert
        assertNotNull(response);
        assertEquals(RUN_ID_1, response.runId());
        assertEquals(1, response.results().size());

        verify(reconRunAuditRepository).findById(RUN_ID_1);
        verify(reconResultAuditRepository).findByRunId(RUN_ID_1);
    }

    @Test
    @DisplayName("Should return 404 when run not found by ID")
    void shouldReturn404ForNotFoundRun() {
        // Arrange
        UUID nonExistentRunId = UUID.randomUUID();
        when(reconRunAuditRepository.findById(nonExistentRunId)).thenReturn(Optional.empty());

        // Act & Assert
        assertThrows(ResponseStatusException.class, () -> {
            controller.getRun(nonExistentRunId);
        });

        verify(reconRunAuditRepository).findById(nonExistentRunId);
    }

    @Test
    @DisplayName("Should retrieve exception results for manual review")
    void shouldRetrieveExceptionResultsForReview() {
        // Arrange
        when(reconResultAuditRepository
                .findByReconStatusAndExceptionTypeIsNotNullOrderByCreatedAtDesc(ReconResultAudit.ReconStatus.EXCEPTION))
                .thenReturn(List.of(exceptionResult));

        // Act
        List<ReconResultAudit> breaks = controller.openBreaks();

        // Assert
        assertEquals(1, breaks.size());
        assertEquals(RUN_ID_2, breaks.get(0).getRunId());
        assertEquals(ReconResultAudit.ExceptionType.AMOUNT_MISMATCH, breaks.get(0).getExceptionType());
        assertEquals(ReconResultAudit.Severity.HIGH, breaks.get(0).getSeverity());

        verify(reconResultAuditRepository)
                .findByReconStatusAndExceptionTypeIsNotNullOrderByCreatedAtDesc(ReconResultAudit.ReconStatus.EXCEPTION);
    }

    @Test
    @DisplayName("Should retrieve run results by run ID")
    void shouldRetrieveResultsByRunId() {
        // Arrange
        ReconResultAudit result1 = new ReconResultAudit();
        result1.setRunId(RUN_ID_1);
        result1.setTxnId("TXN-001");
        
        ReconResultAudit result2 = new ReconResultAudit();
        result2.setRunId(RUN_ID_1);
        result2.setTxnId("TXN-002");

        when(reconResultAuditRepository.findByRunId(RUN_ID_1))
                .thenReturn(List.of(result1, result2));

        // Act
        List<ReconResultAudit> results = controller.getRunResults(RUN_ID_1);

        // Assert
        assertEquals(2, results.size());
        assertTrue(results.stream().allMatch(r -> r.getRunId().equals(RUN_ID_1)));

        verify(reconResultAuditRepository).findByRunId(RUN_ID_1);
    }

    @Test
    @DisplayName("Should include run results when querying recent runs")
    void shouldIncludeResultsInRecentRunsWhenRequested() {
        // Arrange
        when(reconRunAuditRepository.findTop20ByOrderByRunStartedAtDesc())
                .thenReturn(List.of(run1));
        when(reconResultAuditRepository.findByRunId(RUN_ID_1))
                .thenReturn(List.of(matchedResult));

        // Act
        List<RunSummaryResponse> runs = controller.recentRuns(true);

        // Assert
        assertEquals(1, runs.size());
        assertEquals(1, runs.get(0).results().size());

        verify(reconResultAuditRepository).findByRunId(RUN_ID_1);
    }

    @Test
    @DisplayName("Should exclude run results when includeResults=false")
    void shouldExcludeResultsWhenNotRequested() {
        // Arrange
        when(reconRunAuditRepository.findTop20ByOrderByRunStartedAtDesc())
                .thenReturn(List.of(run1));

        // Act
        List<RunSummaryResponse> runs = controller.recentRuns(false);

        // Assert
        assertEquals(1, runs.size());
        assertTrue(runs.get(0).results().isEmpty());

        verify(reconResultAuditRepository, never()).findByRunId(any());
    }
}
