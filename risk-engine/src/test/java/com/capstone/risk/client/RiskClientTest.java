package com.capstone.risk.client;

import com.capstone.common.exception.SecurityCheckUnavailableException;
import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.service.RiskEvaluationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskClientTest {

    @Test
    @DisplayName("RiskClient returns response when completed within 200ms SLA")
    void testEvaluateWithSla_success() {
        RiskEvaluationService mockService = mock(RiskEvaluationService.class);
        RiskClient client = new RiskClient(mockService, Duration.ofMillis(200));

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-sla-fast")
                .customerId("cust-fast")
                .amount(new BigDecimal("100.0000"))
                .currency("PHP")
                .build();

        RiskEvaluationResponse expected = RiskEvaluationResponse.builder()
                .score(0.00)
                .level("LOW")
                .decision("ALLOW")
                .build();

        when(mockService.evaluate(any())).thenReturn(expected);

        RiskEvaluationResponse actual = client.evaluateWithSla(request);
        assertEquals(expected.getScore(), actual.getScore());
        assertEquals("ALLOW", actual.getDecision());
    }

    @Test
    @DisplayName("RiskClient fails closed with SecurityCheckUnavailableException when SLA > 200ms is breached")
    void testEvaluateWithSla_timeoutBreach_failsClosed() {
        RiskEvaluationService mockService = mock(RiskEvaluationService.class);
        // Set short timeout (50ms) to simulate SLA breach quickly
        RiskClient client = new RiskClient(mockService, Duration.ofMillis(50));

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-sla-slow")
                .customerId("cust-slow")
                .amount(new BigDecimal("100.0000"))
                .currency("PHP")
                .build();

        when(mockService.evaluate(any())).thenAnswer(invocation -> {
            Thread.sleep(150); // Intentionally breach timeout
            return RiskEvaluationResponse.builder().score(0.00).decision("ALLOW").build();
        });

        assertThrows(SecurityCheckUnavailableException.class, () -> client.evaluateWithSla(request),
                "Breach of SLA must trigger fail-closed SecurityCheckUnavailableException");
    }

    @Test
    @DisplayName("RiskClient fails closed when downstream throws an exception")
    void testEvaluateWithSla_serviceDown_failsClosed() {
        RiskEvaluationService mockService = mock(RiskEvaluationService.class);
        RiskClient client = new RiskClient(mockService, Duration.ofMillis(200));

        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-sla-err")
                .customerId("cust-err")
                .amount(new BigDecimal("100.0000"))
                .currency("PHP")
                .build();

        when(mockService.evaluate(any())).thenThrow(new RuntimeException("Connection refused"));

        assertThrows(SecurityCheckUnavailableException.class, () -> client.evaluateWithSla(request),
                "Downstream failure must trigger fail-closed SecurityCheckUnavailableException");
    }
}
