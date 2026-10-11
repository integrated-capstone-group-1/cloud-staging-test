package com.capstone.accounts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "ledger_entry")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LedgerEntry {

    @Id
    @Column(name = "entry_id", length = 36, nullable = false, updatable = false)
    private String entryId;

    @Column(name = "txn_id", length = 64, nullable = false)
    private String txnId;

    @Column(name = "leg_no", nullable = false)
    private Integer legNo;

    @Column(name = "account_id", length = 36, nullable = false)
    private String accountId;

    @Column(name = "direction", length = 10, nullable = false)
    private String direction;

    @Column(name = "amount", precision = 18, scale = 4, nullable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", columnDefinition = "CHAR(3)", nullable = false)
    private String currencyCode;

    @Column(name = "running_balance", precision = 18, scale = 4, nullable = false)
    private BigDecimal runningBalance;

    @Column(name = "value_date", nullable = false)
    private LocalDateTime valueDate;

    @Column(name = "business_date", nullable = false)
    private LocalDateTime businessDate;

    @Column(name = "narrative", length = 255)
    private String narrative;

    @Column(name = "hold_id", length = 36)
    private String holdId;

    @Column(name = "reversal_of_entry", length = 36)
    private String reversalOfEntry;

    @Column(name = "posted_at")
    private LocalDateTime postedAt;
}
