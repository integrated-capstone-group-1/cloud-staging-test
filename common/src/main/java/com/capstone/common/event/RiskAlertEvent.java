package com.capstone.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Event emitted when the Java Risk & Fraud Screening Engine flags a high-risk transaction (DIG-79).
 * Published to Kafka topic 'risk.alert' for administrative dashboard review and real-time operations alerts.
 */
public record RiskAlertEvent(
        String alertId,
        String transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        double riskScore,
        String riskLevel,
        String riskDecision,
        List<String> triggeredRules,
        Instant timestamp,
        String reason
) {
}
