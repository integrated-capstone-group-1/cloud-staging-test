package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RecipientLookupResponse(
        @JsonProperty("account_id")
        String accountId,

        @JsonProperty("masked_name")
        String maskedName,

        @JsonProperty("currency")
        String currency
) {}
