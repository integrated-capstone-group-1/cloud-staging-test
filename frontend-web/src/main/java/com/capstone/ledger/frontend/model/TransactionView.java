package com.capstone.ledger.frontend.model;

import com.capstone.ledger.frontend.model.enums.AuditState;
import com.capstone.ledger.frontend.model.enums.MutationDirection;
import com.capstone.ledger.frontend.model.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Frontend view model for a ledger transaction mutation row, mirroring TRANSACTION_MASTER
 * and LEDGER_MUTATION_AUDIT in the ERD.
 */
public class TransactionView {

    private String mutationId;
    private String txnId;
    private String accountId;
    private String accountNumber;
    private String counterpartyAccountId;
    private String counterpartyAccountNumber;
    private TransactionType txnType;
    private MutationDirection direction;
    private BigDecimal amount;
    private String currencyCode;
    private boolean crossCurrency;
    private BigDecimal fxRate;
    private BigDecimal destAmount;
    private String destCurrencyCode;
    private AuditState auditState;
    private LocalDateTime timestamp;

    public TransactionView() {
        this.currencyCode = "PHP";
        this.auditState = AuditState.COMMITTED;
        this.timestamp = LocalDateTime.now();
    }

    public TransactionView(String mutationId, String txnId, String accountId,
                           TransactionType txnType, MutationDirection direction,
                           BigDecimal amount, LocalDateTime timestamp, AuditState auditState) {
        this(mutationId, txnId, accountId, null, txnType, direction, amount, "PHP",
             false, null, null, null, auditState, timestamp);
    }

    public TransactionView(String mutationId, String txnId, String accountId,
                           String counterpartyAccountId, TransactionType txnType,
                           MutationDirection direction, BigDecimal amount, String currencyCode,
                           boolean crossCurrency, BigDecimal fxRate, BigDecimal destAmount,
                           String destCurrencyCode, AuditState auditState, LocalDateTime timestamp) {
        this.mutationId = mutationId;
        this.txnId = txnId;
        this.accountId = accountId;
        this.counterpartyAccountId = counterpartyAccountId;
        this.txnType = txnType;
        this.direction = direction;
        this.amount = amount != null ? amount : BigDecimal.ZERO;
        this.currencyCode = currencyCode != null ? currencyCode : "PHP";
        this.crossCurrency = crossCurrency;
        this.fxRate = fxRate;
        this.destAmount = destAmount;
        this.destCurrencyCode = destCurrencyCode;
        this.auditState = auditState != null ? auditState : AuditState.COMMITTED;
        this.timestamp = timestamp != null ? timestamp : LocalDateTime.now();
    }

    public String getCurrencySymbol() {
        return CurrencyMoneyFormat.symbol(currencyCode);
    }

    public String getDestCurrencySymbol() {
        return CurrencyMoneyFormat.symbol(destCurrencyCode);
    }

    public String getFormattedAmount() {
        String prefix = direction != null ? direction.getSign() : "";
        return prefix + CurrencyMoneyFormat.format(amount, currencyCode);
    }

    public String getFormattedDestAmount() {
        if (destAmount == null) return null;
        return CurrencyMoneyFormat.format(destAmount, destCurrencyCode);
    }

    // --- Aliases for compatibility ---
    public String getAcctNo() { return accountId; }
    public void setAcctNo(String acctNo) { this.accountId = acctNo; }

    // --- Getters & Setters ---
    public String getMutationId() { return mutationId; }
    public void setMutationId(String mutationId) { this.mutationId = mutationId; }

    public String getTxnId() { return txnId; }
    public void setTxnId(String txnId) { this.txnId = txnId; }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }

    public String getCounterpartyAccountId() { return counterpartyAccountId; }
    public void setCounterpartyAccountId(String counterpartyAccountId) { this.counterpartyAccountId = counterpartyAccountId; }

    public TransactionType getTxnType() { return txnType; }
    public void setTxnType(TransactionType txnType) { this.txnType = txnType; }

    public MutationDirection getDirection() { return direction; }
    public void setDirection(MutationDirection direction) { this.direction = direction; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getCurrencyCode() { return currencyCode; }
    public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

    public boolean isCrossCurrency() { return crossCurrency; }
    public void setCrossCurrency(boolean crossCurrency) { this.crossCurrency = crossCurrency; }

    public BigDecimal getFxRate() { return fxRate; }
    public void setFxRate(BigDecimal fxRate) { this.fxRate = fxRate; }

    public BigDecimal getDestAmount() { return destAmount; }
    public void setDestAmount(BigDecimal destAmount) { this.destAmount = destAmount; }

    public String getDestCurrencyCode() { return destCurrencyCode; }
    public void setDestCurrencyCode(String destCurrencyCode) { this.destCurrencyCode = destCurrencyCode; }

    public AuditState getAuditState() { return auditState; }
    public void setAuditState(AuditState auditState) { this.auditState = auditState; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getAccountNumber() { return accountNumber; }
    public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

    public String getCounterpartyAccountNumber() { return counterpartyAccountNumber; }
    public void setCounterpartyAccountNumber(String counterpartyAccountNumber) { this.counterpartyAccountNumber = counterpartyAccountNumber; }

    public String getFormattedAccountNumber() {
        if (accountNumber == null || accountNumber.isBlank()) {
            return accountId;
        }
        if (accountNumber.length() == 12 && accountNumber.matches("\\d+")) {
            return accountNumber.substring(0, 4) + "-" + accountNumber.substring(4, 8) + "-" + accountNumber.substring(8);
        }
        return accountNumber;
    }

    public String getFormattedCounterpartyAccountNumber() {
        if (counterpartyAccountNumber == null || counterpartyAccountNumber.isBlank()) {
            return counterpartyAccountId;
        }
        if (counterpartyAccountNumber.length() == 12 && counterpartyAccountNumber.matches("\\d+")) {
            return counterpartyAccountNumber.substring(0, 4) + "-" + counterpartyAccountNumber.substring(4, 8) + "-" + counterpartyAccountNumber.substring(8);
        }
        return counterpartyAccountNumber;
    }

    public String getShortAccountId() {
        if (accountId != null && accountId.length() > 8) {
            return accountId.substring(0, 8) + "...";
        }
        return accountId != null ? accountId : "";
    }

    public String getShortCounterpartyAccountId() {
        if (counterpartyAccountId != null && counterpartyAccountId.length() > 8) {
            return counterpartyAccountId.substring(0, 8) + "...";
        }
        return counterpartyAccountId != null ? counterpartyAccountId : "";
    }
}
