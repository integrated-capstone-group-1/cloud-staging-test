package com.capstone.risk.controller;

import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.service.RiskEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class RiskEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RiskEvaluationService riskEvaluationService;

    @Test
    @DisplayName("POST /internal/risk/evaluate matches CONTRACTS.md slice")
    void testEvaluate_success() throws Exception {
        RiskEvaluationRequest request = RiskEvaluationRequest.builder()
                .transactionId("txn-5541-uuid")
                .customerId("cust-user-001")
                .amount(new BigDecimal("2517.2000"))
                .currency("PHP")
                .ipAddress("192.168.1.50")
                .deviceId("dev-fingerprint-99")
                .build();

        RiskEvaluationResponse mockResponse = RiskEvaluationResponse.builder()
                .score(0.15)
                .level("LOW")
                .decision("ALLOW")
                .triggeredRules(List.of())
                .evaluationLatencyMs(14.5)
                .build();

        when(riskEvaluationService.evaluate(any())).thenReturn(mockResponse);

        mockMvc.perform(post("/internal/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.15))
                .andExpect(jsonPath("$.level").value("LOW"))
                .andExpect(jsonPath("$.decision").value("ALLOW"))
                .andExpect(jsonPath("$.triggered_rules").isArray())
                .andExpect(jsonPath("$.evaluation_latency_ms").value(14.5));
    }

    @Test
    @DisplayName("POST /internal/risk/evaluate returns 400 on missing required fields")
    void testEvaluate_validationFailure() throws Exception {
        String invalidJson = """
                {
                  "transaction_id": "",
                  "customer_id": "",
                  "amount": null,
                  "currency": ""
                }
                """;

        mockMvc.perform(post("/internal/risk/evaluate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }
}
