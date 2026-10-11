package com.bank.reconciliation.service;

import com.bank.reconciliation.config.ReconProperties;
import com.bank.reconciliation.dto.LegMatchCandidate;
import com.bank.reconciliation.entity.postgres.LedgerMutationAudit;
import com.bank.reconciliation.entity.postgres.ReconResultAudit;
import com.bank.reconciliation.entity.postgres.ReconRunAudit;
import com.bank.reconciliation.event.ReconciliationDiscrepancyEvent;
import com.bank.reconciliation.event.ReconciliationRunCompletedEvent;
import com.bank.reconciliation.entity.oracle.AccountMaster;
import com.bank.reconciliation.repository.oracle.AccountMasterRepository;
import com.bank.reconciliation.repository.postgres.LedgerMutationAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconResultAuditRepository;
import com.bank.reconciliation.repository.postgres.ReconRunAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * "The internal auditor" — one call to run() is one full recon batch:
 *
 *   1. open a recon_run_audit row (RUNNING)
 *   2. pull both sides of the window and pair them per expected ledger
 *      leg (MatchingService / ExpectedLeg — a TRANSFER yields two legs)
 *   3. classify every leg-pair + every orphan ledger leg (ExceptionClassifier)
 *   4. persist one recon_result_audit row per leg
 *   5. for every EXCEPTION, publish a reconciliation.discrepancy event
 *   6. close the run (COMPLETED/FAILED) and publish a run-completed event
 */
@Service
public class ReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationEngine.class);

    private static final String DISCREPANCY_AGGREGATE_TYPE = "TRANSACTION";
    private static final String RUN_AGGREGATE_TYPE = "RECON_RUN";

    private final MatchingService matchingService;
    private final ExceptionClassifier exceptionClassifier;
    private final ReconRunAuditRepository reconRunAuditRepository;
    private final ReconResultAuditRepository reconResultAuditRepository;
    private final OutboxWriter outboxWriter;
    private final ReconProperties props;
    private final AccountMasterRepository accountMasterRepository;
    private final LedgerMutationAuditRepository ledgerMutationAuditRepository;

    public ReconciliationEngine(MatchingService matchingService,
                                 ExceptionClassifier exceptionClassifier,
                                 ReconRunAuditRepository reconRunAuditRepository,
                                 ReconResultAuditRepository reconResultAuditRepository,
                                 OutboxWriter outboxWriter,
                                 ReconProperties props,
                                 AccountMasterRepository accountMasterRepository,
                                 LedgerMutationAuditRepository ledgerMutationAuditRepository) {
        this.matchingService = matchingService;
        this.exceptionClassifier = exceptionClassifier;
        this.reconRunAuditRepository = reconRunAuditRepository;
        this.reconResultAuditRepository = reconResultAuditRepository;
        this.outboxWriter = outboxWriter;
        this.props = props;
        this.accountMasterRepository = accountMasterRepository;
        this.ledgerMutationAuditRepository = ledgerMutationAuditRepository;
    }

    public ReconRunAudit run(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        log.info("Starting reconciliation run for window [{}, {})", windowStart, windowEnd);

        try {
            MatchingService.WindowData windowData = matchingService.buildCandidates(windowStart, windowEnd);
            return persistRun(windowStart, windowEnd, windowData);
        } catch (Exception e) {
            log.error("Reconciliation run failed for window [{}, {})", windowStart, windowEnd, e);
            return persistFailedRun(windowStart, windowEnd);
        }
    }

    @Transactional("postgresTransactionManager")
    protected ReconRunAudit persistRun(OffsetDateTime windowStart, OffsetDateTime windowEnd,
                                        MatchingService.WindowData windowData) {
        ReconRunAudit run = ReconRunAudit.start(windowStart, windowEnd);
        reconRunAuditRepository.save(run);

        int matched = 0;
        int exceptions = 0;

        for (LegMatchCandidate candidate : windowData.candidates()) {
            ReconResultAudit result = exceptionClassifier.classify(candidate);
            result.setRunId(run.getRunId());
            reconResultAuditRepository.save(result);

            if (result.getReconStatus() == ReconResultAudit.ReconStatus.MATCHED) {
                matched++;
            } else {
                exceptions++;
                publishDiscrepancy(result);
                org.slf4j.MDC.put("component", "reconciliation");
                org.slf4j.MDC.put("event_type", "LEDGER_MISMATCH");
                org.slf4j.MDC.put("transactionId", result.getTxnId());
                log.error("{} mismatch detected for transaction {}. Expected: {} {}, Actual: {} {}", 
                        result.getExceptionType(), result.getTxnId(),
                        result.getExpectedAmount(), result.getExpectedCurrencyCode(),
                        result.getActualAmount(), result.getActualCurrencyCode());
                org.slf4j.MDC.clear();
            }
        }

        for (LedgerMutationAudit orphan : windowData.orphanLedgerLegs()) {
            ReconResultAudit result = exceptionClassifier.classifyOrphan(orphan);
            result.setRunId(run.getRunId());
            reconResultAuditRepository.save(result);
            exceptions++;
            publishDiscrepancy(result);
            org.slf4j.MDC.put("component", "reconciliation");
            org.slf4j.MDC.put("event_type", "LEDGER_MISMATCH");
            org.slf4j.MDC.put("transactionId", result.getTxnId());
            log.error("{} orphan ledger entry detected for transaction {}.", 
                    result.getExceptionType(), result.getTxnId());
            org.slf4j.MDC.clear();
        }

        int totalChecked = windowData.candidates().size() + windowData.orphanLedgerLegs().size();
        run.setTotalTxnChecked(totalChecked);
        run.setTotalMatched(matched);
        run.setTotalExceptions(exceptions);
        run.setRunStatus(ReconRunAudit.Status.COMPLETED);
        run.setRunCompletedAt(OffsetDateTime.now());
        reconRunAuditRepository.save(run);

        outboxWriter.enqueue(RUN_AGGREGATE_TYPE, run.getRunId().toString(),
                props.getTopics().getRunCompleted(), ReconciliationRunCompletedEvent.from(run));

        log.info("Completed run {}: checked={} matched={} exceptions={}",
                run.getRunId(), totalChecked, matched, exceptions);
        return run;
    }

    @Transactional("postgresTransactionManager")
    protected ReconRunAudit persistFailedRun(OffsetDateTime windowStart, OffsetDateTime windowEnd) {
        ReconRunAudit run = ReconRunAudit.start(windowStart, windowEnd);
        run.setRunStatus(ReconRunAudit.Status.FAILED);
        run.setRunCompletedAt(OffsetDateTime.now());
        reconRunAuditRepository.save(run);
        outboxWriter.enqueue(RUN_AGGREGATE_TYPE, run.getRunId().toString(),
                props.getTopics().getRunCompleted(), ReconciliationRunCompletedEvent.from(run));
        return run;
    }

    public ReconRunAudit reconcileAccountBalances() {
        OffsetDateTime now = OffsetDateTime.now();
        log.info("Starting account balance reconciliation run at {}", now);
        List<AccountMaster> activeAccounts = accountMasterRepository.findByAccountStatus("ACTIVE");
        if (activeAccounts == null || activeAccounts.isEmpty()) {
            activeAccounts = accountMasterRepository.findAll();
        }

        try {
            return persistBalanceRun(now, activeAccounts);
        } catch (Exception e) {
            log.error("Account balance reconciliation run failed", e);
            return persistFailedRun(now.minusDays(1), now);
        }
    }

    @Transactional("postgresTransactionManager")
    protected ReconRunAudit persistBalanceRun(OffsetDateTime timestamp, List<AccountMaster> accounts) {
        ReconRunAudit run = ReconRunAudit.start(timestamp.minusDays(1), timestamp);
        reconRunAuditRepository.save(run);

        int matched = 0;
        int exceptions = 0;

        for (AccountMaster account : accounts) {
            BigDecimal actualBalance = account.getBalanceAmount();
            BigDecimal expectedBalance = ledgerMutationAuditRepository.calculateExpectedBalanceForAccount(account.getAccountId());
            if (expectedBalance == null) {
                expectedBalance = BigDecimal.ZERO;
            }

            BigDecimal delta = actualBalance.subtract(expectedBalance).abs();
            ReconResultAudit result = new ReconResultAudit();
            result.setRunId(run.getRunId());
            result.setTxnId("BAL-" + account.getAccountId());
            result.setAccountId(account.getAccountId());
            result.setExpectedCurrencyCode(account.getCurrencyCode());
            result.setActualCurrencyCode(account.getCurrencyCode());
            result.setExpectedAmount(expectedBalance);
            result.setActualAmount(actualBalance);
            result.setTxnStatus(account.getAccountStatus());

            if (delta.compareTo(props.getMatching().getAmountTolerance()) <= 0) {
                result.setReconStatus(ReconResultAudit.ReconStatus.MATCHED);
                result.setExceptionType(null);
                matched++;
            } else {
                result.setReconStatus(ReconResultAudit.ReconStatus.EXCEPTION);
                result.setExceptionType(ReconResultAudit.ExceptionType.BALANCE_DRIFT);
                result.setSeverity(ReconResultAudit.Severity.HIGH);
                exceptions++;
                publishDiscrepancy(result);
                org.slf4j.MDC.put("component", "reconciliation");
                org.slf4j.MDC.put("event_type", "BALANCE_DRIFT");
                org.slf4j.MDC.put("accountId", account.getAccountId());
                log.error("BALANCE_DRIFT detected for account {}. Live Oracle balance: {}, Expected Postgres sum: {}",
                        account.getAccountId(), actualBalance, expectedBalance);
                org.slf4j.MDC.clear();
            }
            reconResultAuditRepository.save(result);
        }

        run.setTotalTxnChecked(accounts.size());
        run.setTotalMatched(matched);
        run.setTotalExceptions(exceptions);
        run.setRunStatus(ReconRunAudit.Status.COMPLETED);
        run.setRunCompletedAt(OffsetDateTime.now());
        reconRunAuditRepository.save(run);

        outboxWriter.enqueue(RUN_AGGREGATE_TYPE, run.getRunId().toString(),
                props.getTopics().getRunCompleted(), ReconciliationRunCompletedEvent.from(run));

        log.info("Completed balance recon run {}: accountsChecked={} matched={} exceptions={}",
                run.getRunId(), accounts.size(), matched, exceptions);
        return run;
    }

    private void publishDiscrepancy(ReconResultAudit result) {
        outboxWriter.enqueue(DISCREPANCY_AGGREGATE_TYPE, result.getTxnId(),
                props.getTopics().getDiscrepancy(), ReconciliationDiscrepancyEvent.from(result));
    }
}
