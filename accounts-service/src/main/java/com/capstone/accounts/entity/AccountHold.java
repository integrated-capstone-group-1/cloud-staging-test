package com.capstone.accounts.entity;

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
 * Maps to ACCOUNT_HOLD (Oracle XE 21c).
 * Owned exclusively by accounts-service for amount reservation & risk holds.
 */
@Entity
@Table(name = "account_hold")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccountHold {

    @Id
    @Column(name = "hold_id", length = 36, nullable = false, updatable = false)
    private String holdId;

    @Column(name = "account_id", length = 36, nullable = false)
    private String accountId;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", columnDefinition = "CHAR(3)", nullable = false)
    private String currencyCode;

    @Column(name = "hold_type", length = 20, nullable = false)
    private String holdType;

    @Builder.Default
    @Column(name = "status", length = 20, nullable = false)
    private String status = "ACTIVE";

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 50, nullable = false)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;
    @Column(name = "captured_amount", precision = 18, scale = 4)
    private BigDecimal capturedAmount;

    @Column(name = "placed_by", length = 50)
    private String placedBy;

    @Column(name = "reason_code", length = 20)
    private String reasonCode;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

}
