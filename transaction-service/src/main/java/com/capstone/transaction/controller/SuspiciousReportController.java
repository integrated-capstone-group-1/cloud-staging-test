package com.capstone.transaction.controller;

import com.capstone.transaction.dto.SuspiciousReportRequest;
import com.capstone.transaction.dto.SuspiciousReportResponse;
import com.capstone.transaction.service.SuspiciousReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for reporting suspicious transactions and administering fraud cases (DIG-91 / T-001).
 *
 * Acceptance Criteria:
 * 1) POST /v1/reports/suspicious creates an admin case
 * 2) Kafka event emitted; customer notified
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class SuspiciousReportController {

    private final SuspiciousReportService suspiciousReportService;

    /**
     * AC #1 & #2: POST /v1/reports/suspicious creates an admin case, emits Kafka event, notifies customer.
     */
    @PostMapping({"/v1/reports/suspicious", "/api/v1/reports/suspicious"})
    public ResponseEntity<SuspiciousReportResponse> reportSuspicious(
            @Valid @RequestBody SuspiciousReportRequest request) {

        log.info("Received suspicious transaction report request: txnId={}, reason={}",
                request.getTransactionId(), request.getReason());

        SuspiciousReportResponse response = suspiciousReportService.reportSuspiciousTransaction(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Convenience endpoint: POST /api/v1/transfers/{id}/report-suspicious or /v1/transactions/{id}/report-suspicious
     */
    @PostMapping({"/api/v1/transfers/{id}/report-suspicious", "/v1/transactions/{id}/report-suspicious"})
    public ResponseEntity<SuspiciousReportResponse> reportSuspiciousByPath(
            @PathVariable("id") String id,
            @RequestBody(required = false) SuspiciousReportRequest request) {

        SuspiciousReportRequest finalRequest = request != null ? request : new SuspiciousReportRequest();
        finalRequest.setTransactionId(id);
        if (finalRequest.getReason() == null || finalRequest.getReason().isBlank()) {
            finalRequest.setReason("CUSTOMER_DISPUTE");
        }

        SuspiciousReportResponse response = suspiciousReportService.reportSuspiciousTransaction(finalRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Admin Queue endpoint: GET /v1/reports/suspicious
     */
    @GetMapping({"/v1/reports/suspicious", "/api/v1/reports/suspicious"})
    public ResponseEntity<List<SuspiciousReportResponse>> getAdminCaseQueue(
            @RequestParam(value = "status", required = false) String status) {

        log.info("Querying admin suspicious reports queue with status={}", status);
        List<SuspiciousReportResponse> queue = suspiciousReportService.getAdminCaseQueue(status);
        return ResponseEntity.ok(queue);
    }

    /**
     * Admin Case lookup: GET /v1/reports/suspicious/{caseId}
     */
    @GetMapping({"/v1/reports/suspicious/{caseId}", "/api/v1/reports/suspicious/{caseId}"})
    public ResponseEntity<SuspiciousReportResponse> getCaseById(@PathVariable("caseId") String caseId) {
        log.info("Querying dispute case: {}", caseId);
        SuspiciousReportResponse response = suspiciousReportService.getCaseById(caseId);
        return ResponseEntity.ok(response);
    }
}
