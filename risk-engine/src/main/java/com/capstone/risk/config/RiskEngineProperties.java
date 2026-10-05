package com.capstone.risk.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Data
@Component
@ConfigurationProperties(prefix = "risk")
public class RiskEngineProperties {

    private Rules rules = new Rules();
    private Thresholds thresholds = new Thresholds();

    @Data
    public static class Rules {
        private Weights weights = new Weights();
        private Velocity velocity = new Velocity();
        private Amount amount = new Amount();
        private Device device = new Device();
    }

    @Data
    public static class Weights {
        private double velocity = 0.40;
        private double amount = 0.35;
        private double device = 0.25;
    }

    @Data
    public static class Velocity {
        private int maxTransfers = 3;
        private long windowMinutes = 5;
    }

    @Data
    public static class Amount {
        private BigDecimal phpThreshold = new BigDecimal("100000.0000");
        private BigDecimal amlaStructuringLower = new BigDecimal("400000.0000");
        private BigDecimal amlaStructuringUpper = new BigDecimal("500000.0000");
    }

    @Data
    public static class Device {
        private boolean strictTrustCheck = true;
    }

    @Data
    public static class Thresholds {
        private double lowMax = 0.50;    // Score < 0.50 => LOW / ALLOW
        private double mediumMax = 0.85; // 0.50 <= Score <= 0.85 => MEDIUM / STEP_UP_OTP; > 0.85 => HIGH / HOLD
    }
}
