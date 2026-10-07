package com.capstone.common.event;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Event published to Kafka topic 'txn.lifecycle' on every transaction state transition.
 * Consumed by monitoring-service (DIG-101) and audit telemetry.
 */
public record TransactionLifecycleEvent(
        String txnId,
        String customerId,
        String debitAccountId,
        String creditAccountId,
        BigDecimal amount,
        String currencyCode,
        String txnStatus,
        String stage,
        String reason,
        Instant timestamp
) {
    public static TransactionLifecycleEvent of(
            String txnId,
            String customerId,
            String debitAccountId,
            String creditAccountId,
            BigDecimal amount,
            String currencyCode,
            String txnStatus,
            String stage,
            String reason) {
        return new TransactionLifecycleEvent(
                txnId, customerId, debitAccountId, creditAccountId, amount, currencyCode, txnStatus, stage, reason, Instant.now()
        );
    }
}
