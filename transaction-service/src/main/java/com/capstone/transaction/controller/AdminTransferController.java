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

    @PostMapping({"/transfers/{id}/reverse", "/transactions/{id}/reverse"})
    public ResponseEntity<ReversalResponse> reverseTransfer(
            @PathVariable("id") String id,
            @RequestBody(required = false) ReversalRequest request) {

        log.info("Received administrative reversal request for transaction: {}", id);
        ReversalResponse response = reversalCoordinator.reverseTransaction(id, request);
        return ResponseEntity.ok(response);
    }
}
