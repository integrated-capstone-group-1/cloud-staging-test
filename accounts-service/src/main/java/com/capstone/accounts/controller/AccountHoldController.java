package com.capstone.accounts.controller;

import com.capstone.accounts.service.AccountHoldService;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/accounts")
@RequiredArgsConstructor
public class AccountHoldController {

    private final AccountHoldService accountHoldService;

    @PostMapping("/{accountId}/holds")
    public ResponseEntity<HoldResponse> createHold(
            @PathVariable("accountId") String accountId,
            @RequestBody CreateHoldRequest request) {
        HoldResponse response = accountHoldService.createHold(accountId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{accountId}/holds/{holdId}")
    public ResponseEntity<ReleaseHoldResponse> releaseHold(
            @PathVariable("accountId") String accountId,
            @PathVariable("holdId") String holdId) {
        ReleaseHoldResponse response = accountHoldService.releaseHold(accountId, holdId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{accountId}/holds")
    public ResponseEntity<?> getActiveHolds(@PathVariable("accountId") String accountId) {
        var holds = accountHoldService.getActiveHoldsForAccount(accountId);
        return ResponseEntity.ok(holds);
    }

    @GetMapping("/holds/{holdId}")
    public ResponseEntity<?> getHold(@PathVariable("holdId") String holdId) {
        var hold = accountHoldService.getHold(holdId);
        return ResponseEntity.ok(hold);
    }
}