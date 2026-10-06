package com.capstone.ledger.frontend.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-time Transaction Monitoring Model (DIG-102).
 * Features an 11-stage lifecycle tracker, status chips per TR-MD03,
 * masked customer/account data by default, and stage duration tracking.
 */
public class TransactionMonitoringView {

    private String txnId;
    private String correlationId;
    private String maskedCustomerName;
    private String rawCustomerName;
    private String maskedSourceAccount;
    private String rawSourceAccount;
    private String maskedDestAccount;
    private String rawDestAccount;
    private BigDecimal amount;
    private String currency;
    private String overallStatus; // SUCCESS, PENDING, HELD, FAILED, REVERSED
    private int currentStage;     // 1 to 11
    private long totalDurationMs;
    private LocalDateTime timestamp;
    private List<StageTracker> stages = new ArrayList<>();

    public TransactionMonitoringView() {
        this.timestamp = LocalDateTime.now();
    }

    public static class StageTracker {
        private int sequence;
        private String stageName;
        private String status; // GREEN, BLUE, RED, GREY, OUTLINE
        private long durationMs;

        public StageTracker() {}

        public StageTracker(int sequence, String stageName, String status, long durationMs) {
            this.sequence = sequence;
            this.stageName = stageName;
            this.status = status;
            this.durationMs = durationMs;
        }

        public int getSequence() { return sequence; }
        public void setSequence(int sequence) { this.sequence = sequence; }

        public String getStageName() { return stageName; }
        public void setStageName(String stageName) { this.stageName = stageName; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public long getDurationMs() { return durationMs; }
        public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

        public String getBadgeClass() {
            return switch (status.toUpperCase()) {
                case "GREEN" -> "bg-success text-white";
                case "BLUE" -> "bg-primary text-white";
                case "RED" -> "bg-danger text-white";
                case "OUTLINE" -> "border border-warning text-warning";
                default -> "bg-secondary text-white opacity-50"; // GREY
            };
        }
    }

    public String getFormattedAmount() {
        return CurrencyMoneyFormat.format(amount != null ? amount : BigDecimal.ZERO, currency);
    }

    public String getStatusBadgeClass() {
        return switch (overallStatus != null ? overallStatus.toUpperCase() : "PENDING") {
            case "SUCCESS", "COMPLETED" -> "bg-success text-white";
            case "HELD" -> "bg-warning text-dark";
            case "FAILED" -> "bg-danger text-white";
            case "REVERSED" -> "bg-info text-dark";
            default -> "bg-primary text-white";
        };
    }

    // Getters and Setters
    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getMaskedCustomerName() { return maskedCustomerName; }
    public void setMaskedCustomerName(String maskedCustomerName) { this.maskedCustomerName = maskedCustomerName; }

    public String getRawCustomerName() { return rawCustomerName; }
    public void setRawCustomerName(String rawCustomerName) { this.rawCustomerName = rawCustomerName; }

    public String getMaskedSourceAccount() { return maskedSourceAccount; }
    public void setMaskedSourceAccount(String maskedSourceAccount) { this.maskedSourceAccount = maskedSourceAccount; }

    public String getRawSourceAccount() { return rawSourceAccount; }
    public void setRawSourceAccount(String rawSourceAccount) { this.rawSourceAccount = rawSourceAccount; }

    public String getMaskedDestAccount() { return maskedDestAccount; }
    public void setMaskedDestAccount(String maskedDestAccount) { this.maskedDestAccount = maskedDestAccount; }

    public String getRawDestAccount() { return rawDestAccount; }
    public void setRawDestAccount(String rawDestAccount) { this.rawDestAccount = rawDestAccount; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String overallStatus) { this.overallStatus = overallStatus; }

    public int getCurrentStage() { return currentStage; }
    public void setCurrentStage(int currentStage) { this.currentStage = currentStage; }

    public long getTotalDurationMs() { return totalDurationMs; }
    public void setTotalDurationMs(long totalDurationMs) { this.totalDurationMs = totalDurationMs; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public List<StageTracker> getStages() { return stages; }
    public void setStages(List<StageTracker> stages) { this.stages = stages; }
}
