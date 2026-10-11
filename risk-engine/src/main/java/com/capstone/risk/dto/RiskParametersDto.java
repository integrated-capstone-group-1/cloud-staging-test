package com.capstone.risk.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Data Transfer Object for dynamic parameter store reloading (DIG-22).
 * Allows tuning rule weights, thresholds, velocity windows, and AMLA limits at runtime.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RiskParametersDto {

    // Rule Weights (Normalized composite S = min(1.0, wV*V + wA*A + wD*D + wFx*Fx))
    @JsonProperty("weight_velocity")
    private Double weightVelocity;

    @JsonProperty("weight_amount")
    private Double weightAmount;

    @JsonProperty("weight_device")
    private Double weightDevice;

    @JsonProperty("weight_fx_round_trip")
    private Double weightFxRoundTrip;

    // Decision Thresholds
    @JsonProperty("threshold_low_max")
    private Double thresholdLowMax;

    @JsonProperty("threshold_medium_max")
    private Double thresholdMediumMax;

    // Velocity Constraints
    @JsonProperty("velocity_max_transfers")
    private Integer velocityMaxTransfers;

    @JsonProperty("velocity_window_minutes")
    private Long velocityWindowMinutes;

    // Amount Constraints
    @JsonProperty("amount_php_threshold")
    private BigDecimal amountPhpThreshold;

    @JsonProperty("amount_amla_structuring_lower")
    private BigDecimal amountAmlaStructuringLower;

    @JsonProperty("amount_amla_structuring_upper")
    private BigDecimal amountAmlaStructuringUpper;

    // Device Constraints
    @JsonProperty("device_strict_trust_check")
    private Boolean deviceStrictTrustCheck;

    // Versioning & Audit Metadata
    @JsonProperty("version")
    private Integer version;

    @JsonProperty("last_updated_at")
    private Instant lastUpdatedAt;

    @JsonProperty("updated_by")
    private String updatedBy;

    @JsonProperty("audit_reason")
    private String auditReason;
}
