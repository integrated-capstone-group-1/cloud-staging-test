package com.capstone.risk.controller;

import com.capstone.risk.dto.RiskEvaluationRequest;
import com.capstone.risk.dto.RiskEvaluationResponse;
import com.capstone.risk.service.RiskEvaluationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal Risk Evaluation Controller (CONTRACTS.md Section 3.2).
 * Exposes POST /internal/risk/evaluate for synchronous caller screening.
 * Blocked at edge gateway by network boundary protection (B-06).
 */
import com.capstone.common.dto.ApiResponse;
import com.capstone.common.event.RiskAlertEvent;
import com.capstone.risk.dto.RiskParametersDto;
import com.capstone.risk.kafka.RiskAlertProducer;
import com.capstone.risk.service.RiskParameterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/internal/risk")
@Slf4j
public class RiskEvaluationController {

    private final RiskEvaluationService riskEvaluationService;
    private final RiskParameterService riskParameterService;
    private final RiskAlertProducer riskAlertProducer;

    @Autowired
    public RiskEvaluationController(
            RiskEvaluationService riskEvaluationService,
            @Autowired(required = false) RiskParameterService riskParameterService,
            @Autowired(required = false) RiskAlertProducer riskAlertProducer) {
        this.riskEvaluationService = riskEvaluationService;
        this.riskParameterService = riskParameterService;
        this.riskAlertProducer = riskAlertProducer;
    }

    /**
     * Evaluates transaction risk against velocity, amount anomaly, and device rules.
     * Supports optional X-Simulate-Delay-Ms header for CH-02 SLA breach chaos testing.
     *
     * @param request the risk evaluation request payload
     * @param delayMs optional header to inject artificial delay for chaos experiments
     * @return 200 OK with evaluation outcome
     */
    @PostMapping("/evaluate")
    public ResponseEntity<RiskEvaluationResponse> evaluate(
            @Valid @RequestBody RiskEvaluationRequest request,
            @RequestHeader(value = "X-Simulate-Delay-Ms", required = false) Long delayMs) {

        if (delayMs != null && delayMs > 0) {
            log.warn("Injecting simulated delay of {}ms for chaos testing (CH-02)", delayMs);
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Simulated delay interrupted", e);
            }
        }

        RiskEvaluationResponse response = riskEvaluationService.evaluate(request);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /internal/risk/parameters: Fetches current live risk parameters and rule weights (DIG-22).
     */
    @GetMapping("/parameters")
    public ResponseEntity<ApiResponse<RiskParametersDto>> getParameters() {
        if (riskParameterService == null) {
            return ResponseEntity.ok(ApiResponse.ok("Parameter service unavailable", RiskParametersDto.builder().build()));
        }
        RiskParametersDto params = riskParameterService.getParameters();
        return ResponseEntity.ok(ApiResponse.ok("Live risk parameters retrieved", params));
    }

    /**
     * PUT /internal/risk/parameters: Dynamically reloads risk parameters at runtime without redeployment (DIG-22).
     */
    @PutMapping("/parameters")
    public ResponseEntity<ApiResponse<RiskParametersDto>> updateParameters(
            @RequestBody RiskParametersDto updateReq,
            @RequestHeader(value = "X-Admin-User", defaultValue = "admin@capstonebank.com") String adminUser,
            @RequestHeader(value = "X-Audit-Reason", defaultValue = "Admin parameter update via API") String auditReason) {

        if (riskParameterService == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Parameter service is not available"));
        }

        try {
            RiskParametersDto updated = riskParameterService.updateParameters(updateReq, adminUser, auditReason);
            return ResponseEntity.ok(ApiResponse.ok("Risk parameters dynamically reloaded to v" + updated.getVersion(), updated));
        } catch (IllegalArgumentException ex) {
            log.warn("Validation error updating risk parameters: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
        }
    }

    /**
     * POST /internal/risk/parameters/reset: Resets parameters to factory defaults (DIG-22).
     */
    @PostMapping("/parameters/reset")
    public ResponseEntity<ApiResponse<RiskParametersDto>> resetParameters(
            @RequestHeader(value = "X-Admin-User", defaultValue = "admin@capstonebank.com") String adminUser) {

        if (riskParameterService == null) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Parameter service is not available"));
        }

        RiskParametersDto reset = riskParameterService.resetToDefaults(adminUser);
        return ResponseEntity.ok(ApiResponse.ok("Risk parameters reset to system defaults", reset));
    }

    /**
     * GET /internal/risk/alerts: Returns recent high-risk alert events emitted to Kafka (DIG-79).
     */
    @GetMapping("/alerts")
    public ResponseEntity<ApiResponse<List<RiskAlertEvent>>> getRecentAlerts() {
        List<RiskAlertEvent> alerts = (riskAlertProducer != null) ? riskAlertProducer.getRecentAlerts() : Collections.emptyList();
        return ResponseEntity.ok(ApiResponse.ok("Recent risk alert events retrieved", alerts));
    }
}

