package com.capstone.transaction.service;

import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.client.AccountsServiceClient;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.exception.StateConflictException;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 7-status transaction lifecycle coordinator with Compare-and-Set (CAS) transitions.
 * DIG-80, DIG-83, ADR-002 / T-001.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LifecycleService {

    private final TransactionMasterRepository txnMasterRepository;
    private final AccountsServiceClient accountsServiceClient;

    /**
     * Atomically claims an AUTHORIZED transaction to PROCESSING / SETTLING.
     * Returns true if CAS succeeded, false if aborted (0 rows updated).
     */
    @Transactional("oracleTransactionManager")
    public boolean claimForSettlement(String txnId) {
        int rowsUpdated = txnMasterRepository.updateStatusCas(
                txnId,
                "AUTHORIZED",
                "PROCESSING",
                "SETTLING",
                LocalDateTime.now()
        );
        log.info("CAS claim for settlement on txn {}: rowsUpdated={}", txnId, rowsUpdated);
        return rowsUpdated > 0;
    }

    /**
     * Customer cancel with CAS guard: permitted if INITIATED, AUTHORIZED, or RESERVED.
     * If cancelling from RESERVED status, releases the held funds in CBS via AccountsServiceClient.
     * Throws StateConflictException (HTTP 409) if transaction is already in PROCESSING or terminal.
     */
    @Transactional("oracleTransactionManager")
    public TransactionMaster cancelPayment(String txnId, String reason) {
        TransactionMaster txn = txnMasterRepository.findById(txnId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + txnId + " not found"));

        if ("PROCESSING".equals(txn.getTxnStatus()) || "POSTED".equals(txn.getTxnStatus())
                || "CANCELLED".equals(txn.getTxnStatus()) || "FAILED".equals(txn.getTxnStatus())
                || "REVERSED".equals(txn.getTxnStatus())) {
            throw new StateConflictException("Transaction already in " + txn.getTxnStatus() + " and cannot be cancelled");
        }

        boolean wasReserved = "RESERVED".equals(txn.getTxnStatus());
        String holdId = txn.getCoreReferenceId();
        String debitAccountId = txn.getDebitAccountId();

        int rows = txnMasterRepository.cancelTransactionCas(
                txnId,
                reason != null ? reason : "Customer cancelled payment",
                LocalDateTime.now()
        );

        if (rows == 0) {
            TransactionMaster reloaded = txnMasterRepository.findById(txnId)
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction " + txnId + " not found"));
            throw new StateConflictException("Transaction already in " + reloaded.getTxnStatus() + " and cannot be cancelled");
        }

        if (wasReserved && holdId != null && debitAccountId != null && accountsServiceClient != null) {
            try {
                accountsServiceClient.releaseHold(debitAccountId, holdId);
                log.info("Released CBS hold {} on account {} for cancelled transaction {}", holdId, debitAccountId, txnId);
            } catch (Exception ex) {
                log.error("Failed to release CBS hold {} on account {} for cancelled transaction {}: {}",
                        holdId, debitAccountId, txnId, ex.getMessage());
            }
        }

        txn.setTxnStatus("CANCELLED");
        txn.setStage("CANCELLED");
        txn.setCancelReason(reason);
        txn.setUpdatedAt(LocalDateTime.now());
        return txn;
    }

    @Transactional("oracleTransactionManager")
    public void markPosted(String txnId, String coreReferenceId) {
        txnMasterRepository.markCompleted(
                txnId,
                "POSTED",
                "COMPLETED",
                coreReferenceId,
                LocalDateTime.now()
        );
    }

    @Transactional("oracleTransactionManager")
    public void markFailed(String txnId, String failureReason) {
        txnMasterRepository.markCompleted(
                txnId,
                "FAILED",
                "FAILED",
                null,
                LocalDateTime.now()
        );
    }

    @Transactional("oracleTransactionManager")
    public void markReserved(String txnId, String holdId) {
        txnMasterRepository.markReservedCas(
                txnId,
                holdId,
                LocalDateTime.now()
        );
    }

    @Transactional("oracleTransactionManager")
    public void markReserved(String txnId) {
        markReserved(txnId, null);
    }
}
