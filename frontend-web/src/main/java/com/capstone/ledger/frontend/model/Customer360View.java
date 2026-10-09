package com.capstone.ledger.frontend.model;

import com.capstone.ledger.frontend.model.enums.AccountStatus;
import com.capstone.ledger.frontend.model.enums.AccountType;
import com.capstone.ledger.frontend.model.enums.KycStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Customer 360 Unified Profile Model (DIG-103).
 * Aggregates legal KYC details, multi-currency pockets with Ledger/Held/Available balances,
 * active cards, standing instructions, recent 20 transactions, spending patterns,
 * and explainable risk scores with screening history.
 */
public class Customer360View {

    private String customerId;
    private String fullName;
    private String email;
    private String contactNo;
    private LocalDate birthDate;
    private String address;
    private String idType;
    private String idNumber;
    private KycStatus kycStatus;
    private LocalDateTime memberSince;
    private boolean locked;

    // Currency pockets with 3-tier balances (Ledger, Held, Available)
    private List<PocketBalanceView> pockets = new ArrayList<>();

    // Cards
    private List<CardSummaryView> cards = new ArrayList<>();

    // Standing instructions
    private List<StandingInstructionView> standingInstructions = new ArrayList<>();

    // Last 20 transactions
    private List<TransactionView> recentTransactions = new ArrayList<>();

    // Spending pattern (category -> percentage or total)
    private Map<String, BigDecimal> spendingPattern;

    // Explainable Risk Score (0-100)
    private int riskScore;
    private String riskLevel; // LOW, MEDIUM, HIGH
    private List<String> riskFactors = new ArrayList<>();
    private List<RiskScreeningRecord> screeningHistory = new ArrayList<>();

    // Audit Info
    private LocalDateTime lastAuditedAt;
    private String lastAuditedBy;

    public Customer360View() {
    }

    public BigDecimal getTotalAvailableInPhp() {
        if (pockets == null) return BigDecimal.ZERO;
        return pockets.stream()
                .map(p -> {
                    BigDecimal bal = p.getAvailableBalance() != null ? p.getAvailableBalance() : BigDecimal.ZERO;
                    if ("USD".equalsIgnoreCase(p.getCurrencyCode())) return bal.multiply(new BigDecimal("56.50"));
                    if ("EUR".equalsIgnoreCase(p.getCurrencyCode())) return bal.multiply(new BigDecimal("61.20"));
                    if ("SGD".equalsIgnoreCase(p.getCurrencyCode())) return bal.multiply(new BigDecimal("42.30"));
                    if ("JPY".equalsIgnoreCase(p.getCurrencyCode())) return bal.multiply(new BigDecimal("0.38"));
                    return bal;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public String getFormattedTotalAvailableInPhp() {
        return CurrencyMoneyFormat.format(getTotalAvailableInPhp(), "PHP");
    }

    // --- Inner View Classes ---

    public static class PocketBalanceView {
        private String accountId;
        private String currencyCode;
        private AccountType accountType;
        private AccountStatus accountStatus;
        private BigDecimal ledgerBalance;
        private BigDecimal heldBalance;
        private BigDecimal availableBalance;

        private String accountNumber;

        public PocketBalanceView() {}

        public PocketBalanceView(String accountId, String currencyCode, AccountType accountType,
                                 AccountStatus accountStatus, BigDecimal ledgerBalance,
                                 BigDecimal heldBalance, BigDecimal availableBalance) {
            this(accountId, null, currencyCode, accountType, accountStatus, ledgerBalance, heldBalance, availableBalance);
        }

        public PocketBalanceView(String accountId, String accountNumber, String currencyCode, AccountType accountType,
                                 AccountStatus accountStatus, BigDecimal ledgerBalance,
                                 BigDecimal heldBalance, BigDecimal availableBalance) {
            this.accountId = accountId;
            this.accountNumber = accountNumber;
            this.currencyCode = currencyCode;
            this.accountType = accountType;
            this.accountStatus = accountStatus;
            this.ledgerBalance = ledgerBalance;
            this.heldBalance = heldBalance;
            this.availableBalance = availableBalance;
        }

        public String getAccountNumber() { return accountNumber; }
        public void setAccountNumber(String accountNumber) { this.accountNumber = accountNumber; }

        public String getFormattedAccountNumber() {
            if (accountNumber == null || accountNumber.isBlank()) {
                return accountId;
            }
            if (accountNumber.length() == 12 && accountNumber.matches("\\d+")) {
                return accountNumber.substring(0, 4) + "-" + accountNumber.substring(4, 8) + "-" + accountNumber.substring(8);
            }
            return accountNumber;
        }

        public String getPocketName() {
            return (currencyCode != null ? currencyCode : "PHP") + " Pocket";
        }

        public String getShortAccountId() {
            if (accountId != null && accountId.length() > 8) {
                return accountId.substring(0, 8) + "...";
            }
            return accountId != null ? accountId : "";
        }

        public String getAccountId() { return accountId; }
        public void setAccountId(String accountId) { this.accountId = accountId; }

        public String getCurrencyCode() { return currencyCode; }
        public void setCurrencyCode(String currencyCode) { this.currencyCode = currencyCode; }

        public AccountType getAccountType() { return accountType; }
        public void setAccountType(AccountType accountType) { this.accountType = accountType; }

        public AccountStatus getAccountStatus() { return accountStatus; }
        public void setAccountStatus(AccountStatus accountStatus) { this.accountStatus = accountStatus; }

        public BigDecimal getLedgerBalance() { return ledgerBalance; }
        public void setLedgerBalance(BigDecimal ledgerBalance) { this.ledgerBalance = ledgerBalance; }

        public BigDecimal getHeldBalance() { return heldBalance; }
        public void setHeldBalance(BigDecimal heldBalance) { this.heldBalance = heldBalance; }

        public BigDecimal getAvailableBalance() { return availableBalance; }
        public void setAvailableBalance(BigDecimal availableBalance) { this.availableBalance = availableBalance; }

        public String getFormattedAvailable() {
            return CurrencyMoneyFormat.format(availableBalance != null ? availableBalance : BigDecimal.ZERO, currencyCode);
        }

        public String getFormattedLedger() {
            return CurrencyMoneyFormat.format(ledgerBalance != null ? ledgerBalance : BigDecimal.ZERO, currencyCode);
        }

        public String getFormattedHeld() {
            return CurrencyMoneyFormat.format(heldBalance != null ? heldBalance : BigDecimal.ZERO, currencyCode);
        }

        public boolean isFrozen() {
            return accountStatus == AccountStatus.FROZEN;
        }
    }

    public static class CardSummaryView {
        private String cardId;
        private String maskedPan;
        private String cardType;
        private String currency;
        private String status;
        private BigDecimal dailyLimit;
        private boolean ecomEnabled;

        public CardSummaryView() {}

        public CardSummaryView(String cardId, String maskedPan, String cardType, String currency,
                               String status, BigDecimal dailyLimit, boolean ecomEnabled) {
            this.cardId = cardId;
            this.maskedPan = maskedPan;
            this.cardType = cardType;
            this.currency = currency;
            this.status = status;
            this.dailyLimit = dailyLimit;
            this.ecomEnabled = ecomEnabled;
        }

        public String getCardId() { return cardId; }
        public void setCardId(String cardId) { this.cardId = cardId; }

        public String getMaskedPan() { return maskedPan; }
        public void setMaskedPan(String maskedPan) { this.maskedPan = maskedPan; }

        public String getCardType() { return cardType; }
        public void setCardType(String cardType) { this.cardType = cardType; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        public BigDecimal getDailyLimit() { return dailyLimit; }
        public void setDailyLimit(BigDecimal dailyLimit) { this.dailyLimit = dailyLimit; }

        public boolean isEcomEnabled() { return ecomEnabled; }
        public void setEcomEnabled(boolean ecomEnabled) { this.ecomEnabled = ecomEnabled; }

        public String getFormattedDailyLimit() {
            return CurrencyMoneyFormat.format(dailyLimit != null ? dailyLimit : BigDecimal.ZERO, currency != null ? currency : "PHP");
        }
    }

    public static class StandingInstructionView {
        private String instructionId;
        private String beneficiaryName;
        private String destinationAccount;
        private BigDecimal amount;
        private String currency;
        private String frequency;
        private LocalDate nextRunDate;
        private String status;

        public StandingInstructionView() {}

        public StandingInstructionView(String instructionId, String beneficiaryName, String destinationAccount,
                                       BigDecimal amount, String currency, String frequency,
                                       LocalDate nextRunDate, String status) {
            this.instructionId = instructionId;
            this.beneficiaryName = beneficiaryName;
            this.destinationAccount = destinationAccount;
            this.amount = amount;
            this.currency = currency;
            this.frequency = frequency;
            this.nextRunDate = nextRunDate;
            this.status = status;
        }

        public String getFormattedAmount() {
            return CurrencyMoneyFormat.format(amount != null ? amount : BigDecimal.ZERO, currency != null ? currency : "PHP");
        }

        public String getInstructionId() { return instructionId; }
        public void setInstructionId(String instructionId) { this.instructionId = instructionId; }

        public String getBeneficiaryName() { return beneficiaryName; }
        public void setBeneficiaryName(String beneficiaryName) { this.beneficiaryName = beneficiaryName; }

        public String getDestinationAccount() { return destinationAccount; }
        public void setDestinationAccount(String destinationAccount) { this.destinationAccount = destinationAccount; }

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }

        public String getCurrency() { return currency; }
        public void setCurrency(String currency) { this.currency = currency; }

        public String getFrequency() { return frequency; }
        public void setFrequency(String frequency) { this.frequency = frequency; }

        public LocalDate getNextRunDate() { return nextRunDate; }
        public void setNextRunDate(LocalDate nextRunDate) { this.nextRunDate = nextRunDate; }

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
    }

    public static class RiskScreeningRecord {
        private LocalDate date;
        private String ruleChecked;
        private int score;
        private String result;
        private String details;

        public RiskScreeningRecord() {}

        public RiskScreeningRecord(LocalDate date, String ruleChecked, int score, String result, String details) {
            this.date = date;
            this.ruleChecked = ruleChecked;
            this.score = score;
            this.result = result;
            this.details = details;
        }

        public LocalDate getDate() { return date; }
        public void setDate(LocalDate date) { this.date = date; }

        public String getRuleChecked() { return ruleChecked; }
        public void setRuleChecked(String ruleChecked) { this.ruleChecked = ruleChecked; }

        public int getScore() { return score; }
        public void setScore(int score) { this.score = score; }

        public String getResult() { return result; }
        public void setResult(String result) { this.result = result; }

        public String getDetails() { return details; }
        public void setDetails(String details) { this.details = details; }
    }

    // --- Getters & Setters ---

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getContactNo() { return contactNo; }
    public void setContactNo(String contactNo) { this.contactNo = contactNo; }

    public LocalDate getBirthDate() { return birthDate; }
    public void setBirthDate(LocalDate birthDate) { this.birthDate = birthDate; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getIdType() { return idType; }
    public void setIdType(String idType) { this.idType = idType; }

    public String getIdNumber() { return idNumber; }
    public void setIdNumber(String idNumber) { this.idNumber = idNumber; }

    public KycStatus getKycStatus() { return kycStatus; }
    public void setKycStatus(KycStatus kycStatus) { this.kycStatus = kycStatus; }

    public LocalDateTime getMemberSince() { return memberSince; }
    public void setMemberSince(LocalDateTime memberSince) { this.memberSince = memberSince; }

    public boolean isLocked() { return locked; }
    public void setLocked(boolean locked) { this.locked = locked; }

    public List<PocketBalanceView> getPockets() { return pockets; }
    public void setPockets(List<PocketBalanceView> pockets) { this.pockets = pockets; }

    public List<CardSummaryView> getCards() { return cards; }
    public void setCards(List<CardSummaryView> cards) { this.cards = cards; }

    public List<StandingInstructionView> getStandingInstructions() { return standingInstructions; }
    public void setStandingInstructions(List<StandingInstructionView> standingInstructions) { this.standingInstructions = standingInstructions; }

    public List<TransactionView> getRecentTransactions() { return recentTransactions; }
    public void setRecentTransactions(List<TransactionView> recentTransactions) { this.recentTransactions = recentTransactions; }

    public Map<String, BigDecimal> getSpendingPattern() { return spendingPattern; }
    public void setSpendingPattern(Map<String, BigDecimal> spendingPattern) { this.spendingPattern = spendingPattern; }

    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }

    public String getRiskLevel() { return riskLevel; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }

    public List<String> getRiskFactors() { return riskFactors; }
    public void setRiskFactors(List<String> riskFactors) { this.riskFactors = riskFactors; }

    public List<RiskScreeningRecord> getScreeningHistory() { return screeningHistory; }
    public void setScreeningHistory(List<RiskScreeningRecord> screeningHistory) { this.screeningHistory = screeningHistory; }

    public LocalDateTime getLastAuditedAt() { return lastAuditedAt; }
    public void setLastAuditedAt(LocalDateTime lastAuditedAt) { this.lastAuditedAt = lastAuditedAt; }

    public String getLastAuditedBy() { return lastAuditedBy; }
    public void setLastAuditedBy(String lastAuditedBy) { this.lastAuditedBy = lastAuditedBy; }
}
