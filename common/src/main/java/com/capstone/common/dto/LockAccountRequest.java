package com.capstone.common.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LockAccountRequest(
        @JsonProperty("reason")
        String reason
) {}
