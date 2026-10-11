package com.capstone.risk.controller;

import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.service.RiskEvaluationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.capstone.risk.dto.RiskParametersDto;
import com.capstone.risk.service.RiskParameterService;

@SpringBootTest
@AutoConfigureMockMvc
class RiskEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RiskParameterService riskParameterService;

    @MockBean
    private RiskEvaluationService riskEvaluationService;

    @BeforeEach
    void setUp() {
        if (riskParameterService != null) {
            riskParameterService.resetToDefaults("test-setup");
        }
    }

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

    @Test
    @DisplayName("GET /internal/risk/parameters returns 200 OK with live parameters")
    void testGetParameters_success() throws Exception {
        mockMvc.perform(get("/internal/risk/parameters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.weight_velocity").value(0.40))
                .andExpect(jsonPath("$.data.threshold_medium_max").value(0.85));
    }

    @Test
    @DisplayName("PUT /internal/risk/parameters dynamically updates parameters and returns updated version")
    void testUpdateParameters_success() throws Exception {
        RiskParametersDto updateReq = RiskParametersDto.builder()
                .weightVelocity(0.45)
                .thresholdMediumMax(0.80)
                .build();

        mockMvc.perform(put("/internal/risk/parameters")
                        .header("X-Admin-User", "lead-risk-officer@capstonebank.com")
                        .header("X-Audit-Reason", "Seasonal tuning")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.weight_velocity").value(0.45))
                .andExpect(jsonPath("$.data.threshold_medium_max").value(0.80))
                .andExpect(jsonPath("$.data.updated_by").value("lead-risk-officer@capstonebank.com"));
    }

    @Test
    @DisplayName("GET /internal/risk/alerts returns 200 OK with recent alert events list")
    void testGetAlerts_success() throws Exception {
        mockMvc.perform(get("/internal/risk/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray());
    }
}
