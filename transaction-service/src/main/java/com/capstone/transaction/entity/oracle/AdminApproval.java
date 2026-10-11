package com.capstone.transaction.entity.oracle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Maps to ADMIN_APPROVAL table for 4-eyes maker-checker workflows (BR-32, ADR-002).
 */
@Entity
@Table(name = "admin_approval")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminApproval {

    @Id
    @Column(name = "approval_id", length = 36, nullable = false)
    private String approvalId;

    @Column(name = "target_type", length = 30, nullable = false)
    private String targetType; // 'REVERSAL', 'HOLD_REVIEW', 'ACCOUNT_UNFREEZE'

    @Column(name = "target_id", length = 64, nullable = false)
    private String targetId;

    @Lob
    @Column(name = "proposed_payload")
    private String proposedPayload;

    @Column(name = "maker_id", length = 36, nullable = false)
    private String makerId;

    @Column(name = "checker_id", length = 36)
    private String checkerId;

    @Builder.Default
    @Column(name = "status", length = 20, nullable = false)
    private String status = "PENDING"; // 'PENDING', 'APPROVED', 'REJECTED'

    @Column(name = "reason", length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;
}
