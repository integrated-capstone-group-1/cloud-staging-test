package com.capstone.accounts.controller;

import com.capstone.accounts.service.AccountService;
import com.capstone.common.dto.AccountDTO;
import com.capstone.common.dto.AccountLockResponse;
import com.capstone.common.dto.AccountMutationRequest;
import com.capstone.common.dto.AccountMutationResponse;
import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.CreateAccountRequest;
import com.capstone.common.dto.LockAccountRequest;
import com.capstone.common.dto.UnlockAccountRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping
@RequiredArgsConstructor
public class AccountController {

    private final AccountService accountService;

    @PostMapping({"/api/accounts", "/api/v1/accounts"})
    public ResponseEntity<ApiResponse<AccountDTO>> createAccount(
            @Valid @RequestBody CreateAccountRequest request) {

        AccountDTO dto = accountService.createAccount(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created", dto));
    }

    @GetMapping({"/api/accounts/{accountId}", "/api/v1/accounts/{accountId}"})
    public ResponseEntity<ApiResponse<AccountDTO>> getAccount(
            @PathVariable("accountId") String accountId) {

        return ResponseEntity.ok(
                ApiResponse.ok(accountService.getAccount(accountId))
        );
    }

    @GetMapping({"/api/accounts/{accountId}/balance", "/api/v1/accounts/{accountId}/balance"})
    public ResponseEntity<ApiResponse<BigDecimal>> getBalance(
            @PathVariable("accountId") String accountId) {

        return ResponseEntity.ok(
                ApiResponse.ok(accountService.getBalance(accountId))
        );
    }

    @GetMapping({"/api/accounts/customer/{customerId}", "/api/v1/accounts/customer/{customerId}"})
    public ResponseEntity<ApiResponse<List<AccountDTO>>> getAccountsForCustomer(
            @PathVariable("customerId") String customerId) {

        return ResponseEntity.ok(
                ApiResponse.ok(accountService.getAccountsForCustomer(customerId))
        );
    }

    @GetMapping({"/api/accounts/customer/{customerId}/pockets/{currency}", "/api/v1/accounts/customer/{customerId}/pockets/{currency}"})
    public ResponseEntity<ApiResponse<AccountDTO>> getPocket(
            @PathVariable("customerId") String customerId,
            @PathVariable("currency") String currency) {

        return ResponseEntity.ok(
                ApiResponse.ok(accountService.getPocket(customerId, currency))
        );
    }

    @PutMapping({"/api/accounts/{accountId}/lock", "/api/v1/accounts/{accountId}/lock"})
    public ResponseEntity<AccountLockResponse> lockAccount(
            @PathVariable("accountId") String accountId,
            @RequestBody(required = false) LockAccountRequest request) {

        AccountLockResponse response = accountService.lockAccount(accountId, request);
        return ResponseEntity.ok(response);
    }

    @PutMapping({"/api/accounts/{accountId}/unlock", "/api/v1/accounts/{accountId}/unlock"})
    public ResponseEntity<AccountLockResponse> unlockAccount(
            @PathVariable("accountId") String accountId,
            @RequestBody(required = false) UnlockAccountRequest request) {

        AccountLockResponse response = accountService.unlockAccount(accountId, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping({"/api/accounts/{accountId}/debit", "/api/v1/accounts/{accountId}/debit"})
    public ResponseEntity<ApiResponse<AccountMutationResponse>> debit(
            @PathVariable("accountId") String accountId,
            @Valid @RequestBody AccountMutationRequest request) {

        AccountMutationResponse response = accountService.debit(
                accountId,
                request.amount(),
                request.txnId(),
                request.txnType()
        );

        return ResponseEntity.ok(ApiResponse.ok("Debit processed", response));
    }

    @PostMapping({"/api/accounts/{accountId}/credit", "/api/v1/accounts/{accountId}/credit"})
    public ResponseEntity<ApiResponse<AccountMutationResponse>> credit(
            @PathVariable("accountId") String accountId,
            @Valid @RequestBody AccountMutationRequest request) {

        AccountMutationResponse response = accountService.credit(
                accountId,
                request.amount(),
                request.txnId(),
                request.txnType()
        );

        return ResponseEntity.ok(ApiResponse.ok("Credit processed", response));
    }
}

