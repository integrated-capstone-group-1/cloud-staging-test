package com.capstone.risk.service;

import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskParametersDto;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service managing dynamic runtime reloading of risk parameters, rule weights, and thresholds (DIG-22).
 * Enables operations staff to adjust risk sensitivity without deploying or restarting services.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RiskParameterService {

    private final RiskEngineProperties properties;
    private final AtomicInteger versionCounter = new AtomicInteger(1);
    private volatile RiskParametersDto currentParameters;

    @PostConstruct
    public void init() {
        this.currentParameters = snapshotCurrentProperties("SYSTEM_INIT", "Baseline configuration loaded on startup");
        log.info("RiskParameterService initialized with baseline configuration (v{})", currentParameters.getVersion());
    }

    /**
     * Returns the currently active parameters.
     */
    public RiskParametersDto getParameters() {
        return currentParameters;
    }

    /**
     * Dynamically updates risk rule weights, thresholds, and limits at runtime.
     *
     * @param updateReq payload containing modified parameters
     * @param updatedBy username or identity of the admin requesting the change
     * @param auditReason justification for audit compliance
     * @return updated RiskParametersDto
     */
    public synchronized RiskParametersDto updateParameters(RiskParametersDto updateReq, String updatedBy, String auditReason) {
        log.info("Applying dynamic risk parameters update requested by '{}' (reason: '{}')", updatedBy, auditReason);

        // 1. Validation
        validateParameters(updateReq);

        // 2. Apply weights to active properties
        if (updateReq.getWeightVelocity() != null) {
            properties.getRules().getWeights().setVelocity(updateReq.getWeightVelocity());
        }
        if (updateReq.getWeightAmount() != null) {
            properties.getRules().getWeights().setAmount(updateReq.getWeightAmount());
        }
        if (updateReq.getWeightDevice() != null) {
            properties.getRules().getWeights().setDevice(updateReq.getWeightDevice());
        }
        if (updateReq.getWeightFxRoundTrip() != null) {
            properties.getRules().getWeights().setFxRoundTrip(updateReq.getWeightFxRoundTrip());
        }

        // 3. Apply thresholds
        if (updateReq.getThresholdLowMax() != null) {
            properties.getThresholds().setLowMax(updateReq.getThresholdLowMax());
        }
        if (updateReq.getThresholdMediumMax() != null) {
            properties.getThresholds().setMediumMax(updateReq.getThresholdMediumMax());
        }

        // 4. Apply velocity constraints
        if (updateReq.getVelocityMaxTransfers() != null) {
            properties.getRules().getVelocity().setMaxTransfers(updateReq.getVelocityMaxTransfers());
        }
        if (updateReq.getVelocityWindowMinutes() != null) {
            properties.getRules().getVelocity().setWindowMinutes(updateReq.getVelocityWindowMinutes());
        }

        // 5. Apply amount constraints
        if (updateReq.getAmountPhpThreshold() != null) {
            properties.getRules().getAmount().setPhpThreshold(updateReq.getAmountPhpThreshold());
        }
        if (updateReq.getAmountAmlaStructuringLower() != null) {
            properties.getRules().getAmount().setAmlaStructuringLower(updateReq.getAmountAmlaStructuringLower());
        }
        if (updateReq.getAmountAmlaStructuringUpper() != null) {
            properties.getRules().getAmount().setAmlaStructuringUpper(updateReq.getAmountAmlaStructuringUpper());
        }

        // 6. Apply device constraint
        if (updateReq.getDeviceStrictTrustCheck() != null) {
            properties.getRules().getDevice().setStrictTrustCheck(updateReq.getDeviceStrictTrustCheck());
        }

        // 7. Increment version and snapshot
        String user = (updatedBy != null && !updatedBy.isBlank()) ? updatedBy : "admin@capstonebank.com";
        String reason = (auditReason != null && !auditReason.isBlank()) ? auditReason : "Dynamic parameter update";
        this.currentParameters = snapshotCurrentProperties(user, reason);

        log.info("Risk parameters successfully reloaded to version v{}: lowMax={}, mediumMax={}, weights=[V:{}, A:{}, D:{}, FX:{}]",
                currentParameters.getVersion(),
                properties.getThresholds().getLowMax(),
                properties.getThresholds().getMediumMax(),
                properties.getRules().getWeights().getVelocity(),
                properties.getRules().getWeights().getAmount(),
                properties.getRules().getWeights().getDevice(),
                properties.getRules().getWeights().getFxRoundTrip());

        return this.currentParameters;
    }

    /**
     * Resets risk parameters to initial defaults.
     */
    public synchronized RiskParametersDto resetToDefaults(String adminUser) {
        log.warn("Resetting risk parameters to system defaults by '{}'", adminUser);

        properties.getRules().getWeights().setVelocity(0.40);
        properties.getRules().getWeights().setAmount(0.35);
        properties.getRules().getWeights().setDevice(0.25);
        properties.getRules().getWeights().setFxRoundTrip(0.35);

        properties.getThresholds().setLowMax(0.50);
        properties.getThresholds().setMediumMax(0.85);

        properties.getRules().getVelocity().setMaxTransfers(3);
        properties.getRules().getVelocity().setWindowMinutes(5L);

        properties.getRules().getAmount().setPhpThreshold(new BigDecimal("100000.0000"));
        properties.getRules().getAmount().setAmlaStructuringLower(new BigDecimal("400000.0000"));
        properties.getRules().getAmount().setAmlaStructuringUpper(new BigDecimal("500000.0000"));

        properties.getRules().getDevice().setStrictTrustCheck(true);

        this.currentParameters = snapshotCurrentProperties(adminUser, "Reset to defaults");
        return this.currentParameters;
    }

    private void validateParameters(RiskParametersDto req) {
        if (req.getWeightVelocity() != null && req.getWeightVelocity() < 0) {
            throw new IllegalArgumentException("Weight velocity cannot be negative");
        }
        if (req.getWeightAmount() != null && req.getWeightAmount() < 0) {
            throw new IllegalArgumentException("Weight amount cannot be negative");
        }
        if (req.getWeightDevice() != null && req.getWeightDevice() < 0) {
            throw new IllegalArgumentException("Weight device cannot be negative");
        }
        if (req.getWeightFxRoundTrip() != null && req.getWeightFxRoundTrip() < 0) {
            throw new IllegalArgumentException("Weight FX round trip cannot be negative");
        }

        double low = req.getThresholdLowMax() != null ? req.getThresholdLowMax() : properties.getThresholds().getLowMax();
        double med = req.getThresholdMediumMax() != null ? req.getThresholdMediumMax() : properties.getThresholds().getMediumMax();
        if (low <= 0.0 || low >= med || med > 1.0) {
            throw new IllegalArgumentException(String.format(
                    "Invalid thresholds: must satisfy 0.0 < lowMax (%f) < mediumMax (%f) <= 1.0", low, med));
        }

        if (req.getVelocityMaxTransfers() != null && req.getVelocityMaxTransfers() <= 0) {
            throw new IllegalArgumentException("Velocity maxTransfers must be greater than 0");
        }
        if (req.getVelocityWindowMinutes() != null && req.getVelocityWindowMinutes() <= 0) {
            throw new IllegalArgumentException("Velocity windowMinutes must be greater than 0");
        }

        if (req.getAmountPhpThreshold() != null && req.getAmountPhpThreshold().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount PHP threshold must be positive");
        }
        BigDecimal lower = req.getAmountAmlaStructuringLower() != null ? req.getAmountAmlaStructuringLower() : properties.getRules().getAmount().getAmlaStructuringLower();
        BigDecimal upper = req.getAmountAmlaStructuringUpper() != null ? req.getAmountAmlaStructuringUpper() : properties.getRules().getAmount().getAmlaStructuringUpper();
        if (lower.compareTo(upper) >= 0) {
            throw new IllegalArgumentException("AMLA structuring lower bound must be less than upper bound");
        }
    }

    private RiskParametersDto snapshotCurrentProperties(String updatedBy, String auditReason) {
        return RiskParametersDto.builder()
                .weightVelocity(properties.getRules().getWeights().getVelocity())
                .weightAmount(properties.getRules().getWeights().getAmount())
                .weightDevice(properties.getRules().getWeights().getDevice())
                .weightFxRoundTrip(properties.getRules().getWeights().getFxRoundTrip())
                .thresholdLowMax(properties.getThresholds().getLowMax())
                .thresholdMediumMax(properties.getThresholds().getMediumMax())
                .velocityMaxTransfers(properties.getRules().getVelocity().getMaxTransfers())
                .velocityWindowMinutes(properties.getRules().getVelocity().getWindowMinutes())
                .amountPhpThreshold(properties.getRules().getAmount().getPhpThreshold())
                .amountAmlaStructuringLower(properties.getRules().getAmount().getAmlaStructuringLower())
                .amountAmlaStructuringUpper(properties.getRules().getAmount().getAmlaStructuringUpper())
                .deviceStrictTrustCheck(properties.getRules().getDevice().isStrictTrustCheck())
                .version(versionCounter.getAndIncrement())
                .lastUpdatedAt(Instant.now())
                .updatedBy(updatedBy)
                .auditReason(auditReason)
                .build();
    }
}
