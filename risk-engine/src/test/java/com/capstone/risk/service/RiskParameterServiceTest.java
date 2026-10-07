package com.capstone.risk.service;

import com.capstone.risk.config.RiskEngineProperties;
import com.capstone.risk.dto.RiskParametersDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RiskParameterServiceTest {

    private RiskEngineProperties properties;
    private RiskParameterService parameterService;

    @BeforeEach
    void setUp() {
        properties = new RiskEngineProperties();
        parameterService = new RiskParameterService(properties);
        parameterService.init();
    }

    @Test
    @DisplayName("DIG-22: Baseline parameters initialized to v1 with system defaults")
    void getParameters_initialState() {
        RiskParametersDto params = parameterService.getParameters();

        assertThat(params).isNotNull();
        assertThat(params.getVersion()).isEqualTo(1);
        assertThat(params.getWeightVelocity()).isEqualTo(0.40);
        assertThat(params.getWeightAmount()).isEqualTo(0.35);
        assertThat(params.getWeightDevice()).isEqualTo(0.25);
        assertThat(params.getWeightFxRoundTrip()).isEqualTo(0.35);
        assertThat(params.getThresholdLowMax()).isEqualTo(0.50);
        assertThat(params.getThresholdMediumMax()).isEqualTo(0.85);
        assertThat(params.getVelocityMaxTransfers()).isEqualTo(3);
        assertThat(params.getVelocityWindowMinutes()).isEqualTo(5L);
        assertThat(params.getAmountPhpThreshold()).isEqualByComparingTo("100000.0000");
    }

    @Test
    @DisplayName("DIG-22: Dynamic update updates properties, increments version, and records audit trail")
    void updateParameters_success() {
        RiskParametersDto updateReq = RiskParametersDto.builder()
                .weightVelocity(0.50)
                .weightAmount(0.30)
                .thresholdMediumMax(0.80)
                .velocityMaxTransfers(5)
                .amountPhpThreshold(new BigDecimal("150000.0000"))
                .build();

        RiskParametersDto updated = parameterService.updateParameters(
                updateReq, "compliance-officer@capstonebank.com", "Tightening velocity policy for holiday rush");

        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getUpdatedBy()).isEqualTo("compliance-officer@capstonebank.com");
        assertThat(updated.getAuditReason()).isEqualTo("Tightening velocity policy for holiday rush");
        assertThat(updated.getWeightVelocity()).isEqualTo(0.50);
        assertThat(updated.getWeightAmount()).isEqualTo(0.30);
        assertThat(updated.getThresholdMediumMax()).isEqualTo(0.80);
        assertThat(updated.getVelocityMaxTransfers()).isEqualTo(5);
        assertThat(updated.getAmountPhpThreshold()).isEqualByComparingTo("150000.0000");

        // Verify underlying RiskEngineProperties were updated immediately
        assertThat(properties.getRules().getWeights().getVelocity()).isEqualTo(0.50);
        assertThat(properties.getRules().getWeights().getAmount()).isEqualTo(0.30);
        assertThat(properties.getThresholds().getMediumMax()).isEqualTo(0.80);
        assertThat(properties.getRules().getVelocity().getMaxTransfers()).isEqualTo(5);
        assertThat(properties.getRules().getAmount().getPhpThreshold()).isEqualByComparingTo("150000.0000");
    }

    @Test
    @DisplayName("DIG-22: Validation rejects invalid threshold ordering (lowMax >= mediumMax)")
    void updateParameters_invalidThresholds_throwsException() {
        RiskParametersDto invalidReq = RiskParametersDto.builder()
                .thresholdLowMax(0.90)
                .thresholdMediumMax(0.80)
                .build();

        assertThatThrownBy(() -> parameterService.updateParameters(invalidReq, "admin", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must satisfy 0.0 < lowMax");
    }

    @Test
    @DisplayName("DIG-22: Validation rejects negative weights")
    void updateParameters_negativeWeight_throwsException() {
        RiskParametersDto invalidReq = RiskParametersDto.builder()
                .weightVelocity(-0.1)
                .build();

        assertThatThrownBy(() -> parameterService.updateParameters(invalidReq, "admin", "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");
    }

    @Test
    @DisplayName("DIG-22: Reset to defaults restores initial settings")
    void resetToDefaults_success() {
        // Change a weight
        parameterService.updateParameters(RiskParametersDto.builder().weightVelocity(0.70).build(), "admin", "temp test");
        assertThat(properties.getRules().getWeights().getVelocity()).isEqualTo(0.70);

        // Reset
        RiskParametersDto reset = parameterService.resetToDefaults("super-admin");
        assertThat(reset.getWeightVelocity()).isEqualTo(0.40);
        assertThat(properties.getRules().getWeights().getVelocity()).isEqualTo(0.40);
        assertThat(reset.getUpdatedBy()).isEqualTo("super-admin");
    }
}
