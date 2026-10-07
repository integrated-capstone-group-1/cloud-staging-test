package com.capstone.transaction.service.statement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable document model for bank statements, monthly e-statements,
 * and EOD batch reconciliation reports (DIG-92).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatementDocumentModel {

    @Builder.Default
    private String documentTitle = "ACCOUNT TRANSACTION STATEMENT";

    @Builder.Default
    private String reportType = "STATEMENT"; // STATEMENT, MONTHLY, EOD

    @Builder.Default
    private String bankName = "CAPSTONE DIGITAL BANK";

    private String accountNumber;
    private String accountHolderName;
    private String customerId;

    @Builder.Default
    private String currency = "PHP";

    private LocalDate fromDate;
    private LocalDate toDate;

    @Builder.Default
    private LocalDateTime generatedAt = LocalDateTime.now();

    @Builder.Default
    private BigDecimal openingBalance = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal closingBalance = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalDebits = BigDecimal.ZERO;

    @Builder.Default
    private BigDecimal totalCredits = BigDecimal.ZERO;

    private int transactionCount;

    @Builder.Default
    private List<StatementItem> items = new ArrayList<>();

    @Builder.Default
    private Map<String, String> extraSummaryMetrics = new HashMap<>();
}
