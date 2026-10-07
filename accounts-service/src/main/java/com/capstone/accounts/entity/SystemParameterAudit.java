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
 * Immutable audit record for every admin-triggered system parameter change (DIG-17).
 * Written atomically alongside the system_parameter update inside the same Oracle transaction.
 * Captures the full before/after state of the modified parameter.
 */
@Entity
@Table(name = "system_parameter_audit")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemParameterAudit {

    /**
     * Unique audit record ID.
     */
    @Id
    @Column(name = "audit_id", length = 36, nullable = false, updatable = false)
    private String auditId;

    /**
     * The parameter key that was modified.
     */
    @Column(name = "param_key", length = 100, nullable = false)
    private String paramKey;

    /**
     * New (post-update) version number.
     */
    @Column(name = "param_version", nullable = false)
    private Integer paramVersion;

    /**
     * Previous numeric value before the update.
     */
    @Column(name = "old_value_numeric", precision = 18, scale = 4)
    private BigDecimal oldValueNumeric;

    /**
     * New numeric value after the update.
     */
    @Column(name = "new_value_numeric", precision = 18, scale = 4)
    private BigDecimal newValueNumeric;

    /**
     * Previous text value before the update.
     */
    @Column(name = "old_value_text", length = 200)
    private String oldValueText;

    /**
     * New text value after the update.
     */
    @Column(name = "new_value_text", length = 200)
    private String newValueText;

    /**
     * Admin-provided justification / reason for the change (required for audit trail).
     */
    @Column(name = "change_reason", length = 255)
    private String changeReason;

    /**
     * Email or username of the admin who performed the update.
     */
    @Column(name = "changed_by", length = 100, nullable = false)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private LocalDateTime changedAt;
}
