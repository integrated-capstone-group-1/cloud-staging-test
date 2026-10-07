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
 * JPA Entity for the SYSTEM_PARAMETER table (DIG-17).
 * Stores admin-editable, versioned system parameters used by
 * accounts-service and consumed by downstream services.
 *
 * Single-writer rule: only accounts-service writes to this table.
 * Versioning: each update increments param_version (optimistic versioning).
 */
@Entity
@Table(name = "system_parameter")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemParameter {

    /**
     * Unique key identifying the parameter (e.g. "SINGLE_TRANSFER_LIMIT", "FX_SPREAD_PCT").
     */
    @Id
    @Column(name = "param_key", length = 100, nullable = false, updatable = false)
    private String paramKey;

    /**
     * Human-readable description of the parameter's purpose.
     */
    @Column(name = "param_description", length = 255)
    private String paramDescription;

    /**
     * Numeric value for quantity parameters (limits, thresholds).
     * Null for non-numeric parameters; mutually exclusive with paramValueText.
     */
    @Column(name = "param_value_numeric", precision = 18, scale = 4)
    private BigDecimal paramValueNumeric;

    /**
     * Text value for integer or string parameters (e.g. maxFailedLoginAttempts).
     */
    @Column(name = "param_value_text", length = 200)
    private String paramValueText;

    /**
     * Monotonically incrementing version counter.
     * Incremented on each admin-triggered update.
     * Stamped on transaction records as parameter_version.
     */
    @Column(name = "param_version", nullable = false)
    private Integer paramVersion;

    /**
     * Flag marking this parameter as active.
     */
    @Builder.Default
    @Column(name = "is_active", columnDefinition = "CHAR(1)", nullable = false)
    private String isActive = "Y";

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 50, nullable = false)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;
}
