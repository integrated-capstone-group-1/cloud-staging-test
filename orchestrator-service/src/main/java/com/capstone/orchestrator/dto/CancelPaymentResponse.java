package com.capstone.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CancelPaymentResponse {

    @JsonProperty("transaction_id")
    private String transactionId;

    @JsonProperty("status")
    private String status;
}

