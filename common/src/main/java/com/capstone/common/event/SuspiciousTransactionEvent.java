package com.capstone.common.event;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Event emitted when a customer reports a transaction as suspicious (DIG-91).
 * Consumed by fraud monitoring systems and administrative queues.
 */
public record SuspiciousTransactionEvent(
        String caseId,
        String txnId,
        String customerId,
        String reason,
        String notes,
        BigDecimal amount,
        String currency,
        String status,
        LocalDateTime reportedAt
) {
}
