package com.capstone.transaction.service;

import com.capstone.common.exception.ResourceNotFoundException;
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
     * Customer cancel with CAS guard: only permitted if INITIATED or AUTHORIZED.
     * Throws StateConflictException (HTTP 409) if transaction is already in PROCESSING or terminal.
     */
    @Transactional("oracleTransactionManager")
    public TransactionMaster cancelPayment(String txnId, String reason) {
        TransactionMaster txn = txnMasterRepository.findById(txnId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + txnId + " not found"));

        if ("PROCESSING".equals(txn.getTxnStatus()) || "POSTED".equals(txn.getTxnStatus())) {
            throw new StateConflictException("Transaction already in " + txn.getTxnStatus() + " and cannot be cancelled");
        }

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
    public void markReserved(String txnId) {
        txnMasterRepository.updateStatusCas(
                txnId,
                "INITIATED",
                "RESERVED",
                "RISK_HELD",
                LocalDateTime.now()
        );
    }
}
