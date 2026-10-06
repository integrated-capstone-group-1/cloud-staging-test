package com.capstone.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for virtual card information.
 * Only returns masked PAN for PCI-DSS compliance.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardResponse {
    private String cardId;
    private String maskedPan;
    private String expiryDate;
    private String cardStatus;
    private Integer ecomEnabled;
    private java.math.BigDecimal dailyLimit;
}