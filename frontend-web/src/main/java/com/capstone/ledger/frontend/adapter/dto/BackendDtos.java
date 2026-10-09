package com.capstone.ledger.frontend.adapter.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

public class BackendDtos {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterReq(
            String firstName,
            String lastName,
            String email,
            String contactNo,
            LocalDate birthDate,
            String password,
            String role,
            String idType,
            String idNumber,
            String address
    ) {
        public RegisterReq(String firstName, String lastName, String email, String contactNo, LocalDate birthDate, String password, String role) {
            this(firstName, lastName, email, contactNo, birthDate, password, role, null, null, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegisterRes(
            String customerId,
            String firstName,
            String lastName,
            String email
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginReq(
            String email,
            String password
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LoginRes(
            String token,
            String tokenType,
            long expiresInSeconds,
            String customerId,
            String email,
            String role
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CreateAccountReq(
            String customerId,
            String accountType,
            String currencyCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AccountRes(
            String accountId,
            String customerId,
            String accountType,
            String accountStatus,
            BigDecimal balanceAmount,
            String currencyCode,
            Object createdAt,
            BigDecimal heldAmount,
            String accountNumber
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CardRes(
            String cardId,
            String maskedPan,
            String expiryDate,
            String cardStatus,
            Integer ecomEnabled,
            BigDecimal dailyLimit
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MutateReq(
            String accountId,
            String counterpartyAccountId,
            String txnType,
            BigDecimal amount,
            String idempotencyKey
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MutateRes(
            String txnId,
            String accountId,
            String txnType,
            BigDecimal amount,
            BigDecimal balanceAfter,
            String txnStatus,
            String timestamp,
            String targetCurrency,
            BigDecimal exchangeRate,
            BigDecimal targetAmount,
            BigDecimal feeAmount,
            Boolean isCrossCurrency,
            String currencyCode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomerRes(
            String customerId,
            String firstName,
            String lastName,
            String email,
            String contactNo,
            LocalDate birthDate,
            String idType,
            String idNumber,
            String address
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReconRunRes(
            String runId,
            String status,
            java.time.OffsetDateTime windowStart,
            java.time.OffsetDateTime windowEnd,
            java.time.OffsetDateTime startedAt,
            java.time.OffsetDateTime completedAt,
            int totalChecked,
            int totalMatched,
            int totalExceptions,
            java.util.List<ReconResultRes> results
    ) {}

    public record ReconRunReq(
            java.time.OffsetDateTime windowStart,
            java.time.OffsetDateTime windowEnd
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReconResultRes(
            String resultId,
            String runId,
            String txnId,
            String accountId,
            String mutationUuid,
            String duplicateMutationUuid,
            String reconStatus,
            String exceptionType,
            String expectedCurrencyCode,
            String actualCurrencyCode,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            BigDecimal varianceAmount,
            String txnStatus,
            String ledgerAuditState,
            java.time.OffsetDateTime txnCompletedAt,
            java.time.OffsetDateTime ledgerPostedAt,
            Integer postingLagSeconds,
            String severity,
            java.time.OffsetDateTime createdAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NotificationRes(
            String notifId,
            String customerId,
            String message,
            String status,
            java.time.OffsetDateTime createdAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TransactionHistoryRes(
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("status") String status,
            @JsonProperty("stage") String stage,
            @JsonProperty("txn_type") String txnType,
            @JsonProperty("amount") BigDecimal amount,
            @JsonProperty("currency") String currency,
            @JsonProperty("debit_account_id") String debitAccountId,
            @JsonProperty("credit_account_id") String creditAccountId,
            @JsonProperty("customer_id") String customerId,
            @JsonProperty("core_reference_id") String coreReferenceId,
            @JsonProperty("audit_ref") String auditRef,
            @JsonProperty("audit_trail_url") String auditTrailUrl,
            @JsonProperty("is_cross_currency") String isCrossCurrency,
            @JsonProperty("fx_rate") BigDecimal fxRate,
            @JsonProperty("dest_amount") BigDecimal destAmount,
            @JsonProperty("dest_currency_code") String destCurrencyCode,
            @JsonProperty("created_at") Object createdAt,
            @JsonProperty("completed_at") Object completedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PageRes<T>(
            List<T> content,
            int page,
            int size,
            @JsonProperty("total_elements") long totalElements,
            @JsonProperty("total_pages") int totalPages
    ) {}
}

