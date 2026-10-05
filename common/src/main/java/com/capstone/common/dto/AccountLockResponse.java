package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AccountLockResponse(
        @JsonProperty("account_id")
        String accountId,

        @JsonProperty("account_status")
        String accountStatus
) {}
