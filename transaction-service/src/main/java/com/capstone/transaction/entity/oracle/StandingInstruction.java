package com.capstone.transaction.entity.oracle;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "standing_instruction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandingInstruction {

    @Id
    @Column(name = "instruction_id", length = 36, nullable = false)
    private String instructionId;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "source_account_id", nullable = false, length = 36)
    private String sourceAccountId;

    @Column(name = "target_account_id", nullable = false, length = 36)
    private String targetAccountId;

    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3, columnDefinition = "CHAR(3)")
    private String currencyCode;

    @Column(name = "frequency", nullable = false, length = 20)
    private String frequency;

    @Column(name = "next_execution_date", nullable = false)
    private LocalDate nextExecutionDate;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Builder.Default
    @Column(name = "consecutive_failures", nullable = false)
    private Integer consecutiveFailures = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 50)
    private String createdBy;
}
