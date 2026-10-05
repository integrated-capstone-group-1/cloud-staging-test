package com.capstone.transaction.entity.oracle;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Maps to CARD_MASTER (Oracle XE 21c).
 * Virtual card entity with PCI-DSS compliance:
 * - Full PAN is tokenized (card_token), never stored in plaintext
 * - CVV is NEVER persisted in the database
 * - Only masked PAN is stored for display (e.g., 4532-XXXX-XXXX-8821)
 *
 * card_status: ACTIVE, FROZEN, BLOCKED
 * ecom_enabled: 0 (disabled), 1 (enabled)
 */
@Entity
@Table(name = "card_master")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CardMaster {

    @Id
    @Column(name = "card_id", length = 36, updatable = false, nullable = false)
    private String cardId;

    @Column(name = "customer_id", length = 36, nullable = false)
    private String customerId;

    @Column(name = "account_id", length = 36, nullable = false)
    private String accountId;

    @Column(name = "card_token", length = 64, nullable = false, unique = true)
    private String cardToken;

    @Column(name = "masked_pan", length = 19, nullable = false)
    private String maskedPan;

    @Column(name = "expiry_date", length = 5, nullable = false)
    private String expiryDate; // MM/YY

    @Column(name = "card_status", length = 20, nullable = false)
    @Builder.Default
    private String cardStatus = "ACTIVE";

    @Column(name = "ecom_enabled", length = 1, nullable = false)
    @Builder.Default
    private Integer ecomEnabled = 1;

    @Column(name = "daily_limit", precision = 18, scale = 2)
    @Builder.Default
    private BigDecimal dailyLimit = new BigDecimal("50000.00");

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 50, nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    /**
     * Check if card is active for transactions
     */
    public boolean isActive() {
        return "ACTIVE".equals(cardStatus);
    }

    /**
     * Check if e-commerce transactions are enabled
     */
    public boolean isEcomEnabled() {
        return ecomEnabled != null && ecomEnabled == 1;
    }
}