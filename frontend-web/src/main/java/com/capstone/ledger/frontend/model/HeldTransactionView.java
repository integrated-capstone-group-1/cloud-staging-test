package com.capstone.ledger.frontend.model;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Held Transaction Model (DIG-51, DIG-54).
 * Represents transfers held for administrative review by the Risk Engine / AMLA rules.
 */
public class HeldTransactionView {

    private String txnId;
    private String correlationId;
    private String customerId;
    private String customerName;
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
    private String currencyCode;
    private int riskScore;
    private String flaggedReason;
    private String status; // HELD_FOR_REVIEW, APPROVED, REJECTED
    private String reviewedBy;
    private LocalDateTime reviewedAt;
    private String decisionNotes;
    private LocalDateTime timestamp;

    public HeldTransactionView() {
        this.status = "HELD_FOR_REVIEW";
        this.timestamp = LocalDateTime.now();
    }

    public HeldTransactionView(String txnId, String correlationId, String customerId, String customerName,
                               String fromAccount, String toAccount, BigDecimal amount, String currencyCode,
                               int riskScore, String flaggedReason, String status, LocalDateTime timestamp) {
        this.txnId = txnId;
        this.correlationId = correlationId;
        this.customerId = customerId;
        this.customerName = customerName;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.currencyCode = currencyCode;
        this.riskScore = riskScore;
        this.flaggedReason = flaggedReason;
        this.status = status != null ? status : "HELD_FOR_REVIEW";
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    public String getFormattedAmount() {
        return CurrencyMoneyFormat.format(amount != null ? amount : BigDecimal.ZERO, currencyCode);
    }

    public boolean isPending() {
        return "HELD_FOR_REVIEW".equalsIgnoreCase(status);
    }

    public boolean isApproved() {
        return "APPROVED".equalsIgnoreCase(status);
    }

    public boolean isRejected() {
        return "REJECTED".equalsIgnoreCase(status);
    }

    // Getters and Setters
    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getCifNumber() {
        return CustomerView.deriveCifNumber(customerId, null);
    }

    public String getShortCustomerId() {
        if (customerId == null) return "";
        return customerId.length() > 8 ? customerId.substring(0, 8) + "..." : customerId;
    }

    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }

    public String getFromAccount() { return fromAccount; }
    public void setFromAccount(String fromAccount) { this.fromAccount = fromAccount; }

    public String getToAccount() { return toAccount; }
    public void setToAccount(String toAccount) { this.toAccount = toAccount; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }

    public String getFlaggedReason() { return flaggedReason; }
    public void setFlaggedReason(String flaggedReason) { this.flaggedReason = flaggedReason; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }

    public LocalDateTime getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(LocalDateTime reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getDecisionNotes() { return decisionNotes; }
    public void setDecisionNotes(String decisionNotes) { this.decisionNotes = decisionNotes; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}

