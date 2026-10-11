package com.capstone.transaction.service;

import com.capstone.common.event.TransactionLifecycleEvent;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.client.AccountsServiceClient;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.exception.StateConflictException;
import com.capstone.transaction.kafka.TransactionEventProducer;
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
    private final TransactionEventProducer transactionEventProducer;

    /**
     * Persists initial INITIATED transaction to Oracle DB and emits event.
     */
    @Transactional("oracleTransactionManager")
    public TransactionMaster createInitiatedTransaction(TransactionMaster txn) {
        TransactionMaster saved = txnMasterRepository.saveAndFlush(txn);
        emitLifecycleEvent(saved, "INITIATED", "RISK_PENDING", null);
        return saved;
    }

    /**
     * Atomically transitions INITIATED transaction to AUTHORIZED / AUTH_CONFIRMED.
     */
    @Transactional("oracleTransactionManager")
    public boolean markAuthorized(String txnId) {
        int rowsUpdated = txnMasterRepository.updateStatusCas(
                txnId,
                "INITIATED",
                "AUTHORIZED",
                "AUTH_CONFIRMED",
                LocalDateTime.now()
        );
        log.info("CAS updateStatusCas to AUTHORIZED for txn {}: updatedRows={}", txnId, rowsUpdated);
        if (rowsUpdated > 0) {
            emitLifecycleEvent(txnId, "AUTHORIZED", "AUTH_CONFIRMED", null);
        }
        return rowsUpdated > 0;
    }

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
        if (rowsUpdated > 0) {
            emitLifecycleEvent(txnId, "PROCESSING", "SETTLING", "Claimed for settlement");
        }
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

        emitLifecycleEvent(txn, "CANCELLED", "CANCELLED", reason);
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
        emitLifecycleEvent(txnId, "POSTED", "COMPLETED", coreReferenceId);
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
        emitLifecycleEvent(txnId, "FAILED", "FAILED", failureReason);
    }

    @Transactional("oracleTransactionManager")
    public void markReserved(String txnId, String holdId) {
        txnMasterRepository.markReservedCas(
                txnId,
                holdId,
                LocalDateTime.now()
        );
        emitLifecycleEvent(txnId, "RESERVED", "RISK_HELD", holdId != null ? ("Hold: " + holdId) : "Risk held");
    }

    @Transactional("oracleTransactionManager")
    public void markReserved(String txnId) {
        markReserved(txnId, null);
    }

    public void emitLifecycleEvent(String txnId, String status, String stage, String reason) {
        if (transactionEventProducer == null) {
            return;
        }
        try {
            txnMasterRepository.findById(txnId).ifPresent(txn -> emitLifecycleEvent(txn, status, stage, reason));
        } catch (Exception ex) {
            log.warn("Could not emit lifecycle event for txn {}: {}", txnId, ex.getMessage());
        }
    }

    public void emitLifecycleEvent(TransactionMaster txn, String status, String stage, String reason) {
        if (transactionEventProducer == null || txn == null) {
            return;
        }
        try {
            TransactionLifecycleEvent event = TransactionLifecycleEvent.of(
                    txn.getTxnId(),
                    txn.getCustomerId(),
                    txn.getDebitAccountId(),
                    txn.getCreditAccountId(),
                    txn.getMutationAmount(),
                    txn.getCurrencyCode(),
                    status,
                    stage,
                    reason
            );
            transactionEventProducer.publishLifecycle(event);
            log.info("Emitted txn.lifecycle event for txn {}: status={}, stage={}", txn.getTxnId(), status, stage);
        } catch (Exception ex) {
            log.warn("Failed to publish txn.lifecycle event for txn {}: {}", txn.getTxnId(), ex.getMessage());
        }
    }
}
