package com.capstone.ledger.frontend.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Versioned System Parameter Store Model (DIG-17, DIG-54, BR-19).
 * Supports administrative configuration of transaction limits, AMLA thresholds,
 * FX spreads, and security lockouts with audit logging and version tracking.
 */
public class SystemParameterView {

    private int version;
    private BigDecimal singleTransferLimit;
    private BigDecimal dailyTransferLimit;
    private BigDecimal amlaReportingThreshold; // Mandatory CTR threshold (e.g. 500,000 PHP)
    private BigDecimal amlaHighRiskThreshold;  // Suspicious STR threshold (e.g. 250,000 PHP)
    private BigDecimal fxSpreadPercentage;     // FX spread markup % (e.g. 1.25)
    private int maxFailedLoginAttempts;        // Auto-lock threshold (e.g. 3)
    private int otpTtlSeconds;                 // OTP expiration (e.g. 300)
    private String updatedBy;
    private LocalDateTime updatedAt;
    private String auditReason;

    public SystemParameterView() {
        this.version = 1;
        this.singleTransferLimit = new BigDecimal("100000.00");
        this.dailyTransferLimit = new BigDecimal("500000.00");
        this.amlaReportingThreshold = new BigDecimal("500000.00");
        this.amlaHighRiskThreshold = new BigDecimal("250000.00");
        this.fxSpreadPercentage = new BigDecimal("1.25");
        this.maxFailedLoginAttempts = 3;
        this.otpTtlSeconds = 300;
        this.updatedBy = "SYSTEM";
        this.updatedAt = LocalDateTime.now();
        this.auditReason = "Initial baseline system parameter configuration";
    }

    public SystemParameterView(int version, BigDecimal singleTransferLimit, BigDecimal dailyTransferLimit,
                               BigDecimal amlaReportingThreshold, BigDecimal amlaHighRiskThreshold,
                               BigDecimal fxSpreadPercentage, int maxFailedLoginAttempts, int otpTtlSeconds,
                               String updatedBy, LocalDateTime updatedAt, String auditReason) {
        this.version = version;
        this.singleTransferLimit = singleTransferLimit;
        this.dailyTransferLimit = dailyTransferLimit;
        this.amlaReportingThreshold = amlaReportingThreshold;
        this.amlaHighRiskThreshold = amlaHighRiskThreshold;
        this.fxSpreadPercentage = fxSpreadPercentage;
        this.maxFailedLoginAttempts = maxFailedLoginAttempts;
        this.otpTtlSeconds = otpTtlSeconds;
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
        this.auditReason = auditReason;
    }

    // Getters and Setters
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public BigDecimal getSingleTransferLimit() { return singleTransferLimit; }
    public void setSingleTransferLimit(BigDecimal singleTransferLimit) { this.singleTransferLimit = singleTransferLimit; }

    public BigDecimal getDailyTransferLimit() { return dailyTransferLimit; }
    public void setDailyTransferLimit(BigDecimal dailyTransferLimit) { this.dailyTransferLimit = dailyTransferLimit; }

    public BigDecimal getAmlaReportingThreshold() { return amlaReportingThreshold; }
    public void setAmlaReportingThreshold(BigDecimal amlaReportingThreshold) { this.amlaReportingThreshold = amlaReportingThreshold; }

    public BigDecimal getAmlaHighRiskThreshold() { return amlaHighRiskThreshold; }
    public void setAmlaHighRiskThreshold(BigDecimal amlaHighRiskThreshold) { this.amlaHighRiskThreshold = amlaHighRiskThreshold; }

    public BigDecimal getFxSpreadPercentage() { return fxSpreadPercentage; }
    public void setFxSpreadPercentage(BigDecimal fxSpreadPercentage) { this.fxSpreadPercentage = fxSpreadPercentage; }

    public int getMaxFailedLoginAttempts() { return maxFailedLoginAttempts; }
    public void setMaxFailedLoginAttempts(int maxFailedLoginAttempts) { this.maxFailedLoginAttempts = maxFailedLoginAttempts; }

    public int getOtpTtlSeconds() { return otpTtlSeconds; }
    public void setOtpTtlSeconds(int otpTtlSeconds) { this.otpTtlSeconds = otpTtlSeconds; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getAuditReason() { return auditReason; }
    public void setAuditReason(String auditReason) { this.auditReason = auditReason; }
}
