package com.capstone.common.event;

import java.math.BigDecimal;
import java.util.List;

public record PaymentCompletedEvent(
        String txnId,
        String coreReferenceId,
        String customerId,
        List<LegDTO> legs
) {}
