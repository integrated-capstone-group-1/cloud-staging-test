package com.capstone.transaction.service.statement;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Line item in a bank statement or report (DIG-92).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatementItem {
    private LocalDateTime dateTime;
    private String referenceId;
    private String txnType;
    private String description;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal balanceAfter;
    private String status;
}
