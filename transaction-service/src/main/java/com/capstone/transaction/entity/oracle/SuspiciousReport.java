package com.capstone.transaction.entity.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to SUSPICIOUS_REPORT (Oracle XE 21c).
 * Persists customer reports of suspicious transactions for administrative fraud investigation (DIG-91).
 */
@Entity
@Table(name = "suspicious_report")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SuspiciousReport {

    @Id
    @Column(name = "case_id", length = 36, nullable = false, updatable = false)
    private String caseId;

    @Column(name = "txn_id", length = 36, nullable = false)
    private String txnId;

    @Column(name = "customer_id", length = 36, nullable = false)
    private String customerId;

    @Column(name = "reason", length = 100, nullable = false)
    private String reason;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "amount", precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", length = 3)
    private String currencyCode;

    @Builder.Default
    @Column(name = "status", length = 30, nullable = false)
    private String status = "OPEN"; // OPEN, UNDER_REVIEW, RESOLVED, REJECTED

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
