package com.capstone.common.event;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record PaymentCompletedEvent(
        @JsonProperty("txn_id") String txnId,
        @JsonProperty("core_reference_id") String coreReferenceId,
        @JsonProperty("customer_id") String customerId,
        @JsonProperty("legs") List<LegDTO> legs
) {}
