package com.capstone.transaction.entity.postgres;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps to RISK_SCREENING_AUDIT (PostgreSQL 15+ Audit DB).
 *
 * Immutably records real-time fraud scoring, rule matches, and decisions
 * (APPROVE, CHALLENGE, REJECT).
 * Append-only at the database layer.
 */
@Entity
@Table(name = "risk_screening_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RiskScreeningAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "screening_id", updatable = false, nullable = false)
    private UUID screeningId;

    @Column(name = "txn_id", nullable = false, length = 36)
    private String txnId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "risk_score", precision = 4, scale = 2, nullable = false)
    private BigDecimal riskScore;

    @Column(name = "risk_level", nullable = false, length = 20)
    private String riskLevel;

    @Column(name = "risk_decision", nullable = false, length = 30)
    private String riskDecision;

    @Column(name = "triggered_rules", columnDefinition = "JSONB", nullable = false)
    private String triggeredRules;

    @Column(name = "evaluation_latency_ms", precision = 8, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal evaluationLatencyMs = BigDecimal.ZERO;

    @Column(name = "evaluated_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime evaluatedAt = OffsetDateTime.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public String getDecision() {
        return this.riskDecision;
    }

    public void setDecision(String decision) {
        this.riskDecision = decision;
    }
}

