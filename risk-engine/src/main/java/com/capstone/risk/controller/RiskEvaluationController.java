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
@RestController
@RequestMapping("/internal/risk")
@Slf4j
public class RiskEvaluationController {

    private final RiskEvaluationService riskEvaluationService;

    public RiskEvaluationController(RiskEvaluationService riskEvaluationService) {
        this.riskEvaluationService = riskEvaluationService;
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
}

