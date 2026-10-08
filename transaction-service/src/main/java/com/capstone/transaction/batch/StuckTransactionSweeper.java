package com.capstone.transaction.batch;

import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.client.CoreBankingClient;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import com.capstone.transaction.service.LifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled sweeper for stuck in-flight transactions (DIG-86 / T-001).
 * Reconciles transactions remaining in PROCESSING state against Core Banking System (CBS).
 * - If CBS confirms settlement: marks POSTED.
 * - If CBS has no record (404 ResourceNotFound): marks FAILED to prevent duplicate processing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StuckTransactionSweeper {

    private final TransactionMasterRepository txnMasterRepository;
    private final CoreBankingClient coreBankingClient;
    private final LifecycleService lifecycleService;

    @Value("${transaction.sweeper.stuck-threshold-seconds:60}")
    private long stuckThresholdSeconds = 60;

    public void setStuckThresholdSeconds(long stuckThresholdSeconds) {
        this.stuckThresholdSeconds = stuckThresholdSeconds;
    }

    @Scheduled(fixedDelayString = "${transaction.sweeper.fixed-delay-ms:30000}")
    public int sweepStuckTransactions() {
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(stuckThresholdSeconds);
        log.debug("Sweeping stuck transactions in PROCESSING prior to {}", cutoff);

        List<TransactionMaster> stuckList = txnMasterRepository.findByTxnStatusAndInitiatedAtBefore("PROCESSING", cutoff);
        if (stuckList == null || stuckList.isEmpty()) {
            return 0;
        }

        log.info("Found {} stuck transactions to reconcile with CBS", stuckList.size());
        int resolvedCount = 0;

        for (TransactionMaster txn : stuckList) {
            String txnId = txn.getTxnId();
            try {
                CoreSettlementResponse settlement = coreBankingClient.getSettlementStatus(txnId);
                if (settlement != null && "SETTLED".equalsIgnoreCase(settlement.status())) {
                    String ref = settlement.coreReferenceId() != null ? settlement.coreReferenceId() : settlement.postingReferenceId();
                    lifecycleService.markPosted(txnId, ref);
                } else {
                    log.warn("Sweeper: CBS returned non-settled status '{}' for txn {}. Marking FAILED.",
                            settlement != null ? settlement.status() : "null", txnId);
                    lifecycleService.markFailed(txnId, "CBS settlement status not completed");
                }
                resolvedCount++;
            } catch (ResourceNotFoundException ex) {
                log.warn("Sweeper: No settlement found in CBS for stuck txn {}. Marking FAILED.", txnId);
                lifecycleService.markFailed(txnId, "Settlement not found in CBS during stuck sweep");
                resolvedCount++;
            } catch (Exception ex) {
                log.error("Sweeper failed to reconcile txn {} with CBS: {}", txnId, ex.getMessage(), ex);
            }
        }

        return resolvedCount;
    }
}
