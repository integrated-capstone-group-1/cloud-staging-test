package com.capstone.ledger.frontend.model.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * Account types matching the retail banking architecture:
 * SAVINGS (Unified primary deposit account) and WALLET (Multi-currency pocket).
 */
public enum AccountType {
    SAVINGS("Savings Account"),
    WALLET("Digital Wallet");

    private final String displayName;

    AccountType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @JsonCreator
    public static AccountType fromString(String value) {
        if (value == null) {
            return SAVINGS;
        }
        for (AccountType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        // Fallback for legacy CHECKING or other customer account types to SAVINGS
        return SAVINGS;
    }
}

