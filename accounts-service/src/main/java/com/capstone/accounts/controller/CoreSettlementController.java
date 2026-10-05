package com.capstone.accounts.controller;

import com.capstone.accounts.service.CoreSettlementService;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/core/settlement")
@RequiredArgsConstructor
public class CoreSettlementController {

    private final CoreSettlementService coreSettlementService;

    @PostMapping
    public ResponseEntity<CoreSettlementResponse> settle(@RequestBody CoreSettlementRequest request) {
        CoreSettlementResponse response = coreSettlementService.settle(request);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{postingReferenceId}")
    public ResponseEntity<CoreSettlementResponse> getStatus(@PathVariable String postingReferenceId) {
        CoreSettlementResponse response = coreSettlementService.getSettlementStatus(postingReferenceId);
        return ResponseEntity.ok(response);
    }
}