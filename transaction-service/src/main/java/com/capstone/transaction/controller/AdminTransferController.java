package com.capstone.transaction.controller;

import com.capstone.transaction.dto.ReversalRequest;
import com.capstone.transaction.dto.ReversalResponse;
import com.capstone.transaction.service.ReversalCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Admin transfer operations controller: administrative reversals.
 * DIG-87 / T-001.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminTransferController {

    private final ReversalCoordinator reversalCoordinator;
    private final com.capstone.transaction.batch.EodBatchScheduler eodBatchScheduler;

    @PostMapping("/batch/eod")
    public ResponseEntity<java.util.Map<String, String>> triggerEodBatch() {
        log.info("Triggering on-demand EOD batch execution via Admin API");
        eodBatchScheduler.runEodBatch();
        return ResponseEntity.ok(java.util.Map.of("status", "SUCCESS", "message", "EOD batch completed"));
    }

    @PostMapping({"/transfers/{id}/reverse", "/transactions/{id}/reverse"})
    public ResponseEntity<ReversalResponse> reverseTransfer(
            @PathVariable("id") String id,
            @RequestBody(required = false) ReversalRequest request) {

        log.info("Received administrative reversal request for transaction: {}", id);
        ReversalResponse response = reversalCoordinator.reverseTransaction(id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/transfers/{id}/propose-reversal")
    public ResponseEntity<com.capstone.transaction.entity.oracle.AdminApproval> proposeReversal(
            @PathVariable("id") String id,
            @RequestParam(value = "maker_id", defaultValue = "admin-maker") String makerId,
            @RequestBody(required = false) ReversalRequest request) {

        String reason = request != null ? request.getReason() : "Administrative reversal proposed";
        log.info("Maker {} proposing reversal for txn {}", makerId, id);
        com.capstone.transaction.entity.oracle.AdminApproval approval =
                reversalCoordinator.proposeReversal(id, makerId, reason);
        return ResponseEntity.ok(approval);
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public ResponseEntity<ReversalResponse> approveReversal(
            @PathVariable("approvalId") String approvalId,
            @RequestParam(value = "checker_id", defaultValue = "admin-checker") String checkerId) {

        log.info("Checker {} approving reversal {}", checkerId, approvalId);
        ReversalResponse response = reversalCoordinator.approveReversal(approvalId, checkerId);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public ResponseEntity<com.capstone.transaction.entity.oracle.AdminApproval> rejectReversal(
            @PathVariable("approvalId") String approvalId,
            @RequestParam(value = "checker_id", defaultValue = "admin-checker") String checkerId,
            @RequestParam(value = "reason", required = false) String reason) {

        log.info("Checker {} rejecting reversal {}", checkerId, approvalId);
        com.capstone.transaction.entity.oracle.AdminApproval approval =
                reversalCoordinator.rejectReversal(approvalId, checkerId, reason);
        return ResponseEntity.ok(approval);
    }
}
