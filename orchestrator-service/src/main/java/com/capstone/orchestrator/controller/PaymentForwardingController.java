package com.capstone.orchestrator.controller;

import com.capstone.common.security.SecurityUtils;
import com.capstone.orchestrator.client.TransactionClient;
import com.capstone.orchestrator.dto.CancelPaymentResponse;
import com.capstone.orchestrator.dto.PaymentRequest;
import com.capstone.orchestrator.dto.PaymentResponse;
import com.capstone.orchestrator.service.AccountOwnershipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Tier 3 Channel Controller for Payment Journeys.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: Entrypoint for retail mobile and web payment submissions (POST /api/v1/payments).
 * - HOW:
 *   1. Intercepts incoming JWT request from Edge Gateway (:8080).
 *   2. Enforces customer account ownership on senderAccountId via AccountOwnershipService.
 *   3. Forwards validated request to Transaction Service (:8084) via RestClient.
 * - WHY: Decouples presentation/ownership checks from core balance mutation.
 *        Never calls CBS settlement.
 *
 * TRACEABILITY CALL PATH:
 * Client -> Gateway (:8080) -> PaymentForwardingController (:8089)
 *   -> verifyAccountOwnership()
 *   -> TransactionClient -> Transaction Service (:8084) -> CBS (:8083)
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentForwardingController {

    private final AccountOwnershipService ownershipService;
    private final TransactionClient transactionClient;

    @PostMapping
    public ResponseEntity<PaymentResponse> makePayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        log.info("BFF: Received payment request: recipient={}, amount={} {}",
                request.getRecipientAccountId(), request.getAmount(), request.getCurrency());

        // Step 1: Enforce customer ownership on funding pocket and sender account
        if (request.getFundingPocketId() != null && !request.getFundingPocketId().isBlank()) {
            if (request.getQuoteId() == null || request.getQuoteId().isBlank()) {
                throw new IllegalArgumentException("quote_id is required when funding_pocket_id is specified");
            }
            ownershipService.verifyAccountOwnership(request.getFundingPocketId(), "funding pocket debit");
        }

        if (request.getSenderAccountId() != null) {
            ownershipService.verifyAccountOwnership(request.getSenderAccountId(), "debit payment");
        } else {
            String customerId = SecurityUtils.getCurrentCustomerId().orElse(null);
            String defaultAcct = ownershipService.resolveDefaultAccountId(customerId, request.getCurrency());
            if (defaultAcct != null) {
                request.setSenderAccountId(defaultAcct);
                log.info("BFF: Resolved default debit account {} for customer {}", defaultAcct, customerId);
            }
        }

        // Step 2: Forward to Tier 4 Payment Hub (:8084)
        PaymentResponse response = transactionClient.forwardPayment(request, idempotencyKey, authHeader);
        if (response == null) {
            throw new IllegalStateException("Payment response was null from payment hub");
        }

        // Step 3: Map 202 Accepted for stepped-up verification or risk hold queues
        if ("RESERVED".equals(response.getStatus()) || "STEP_UP_OTP".equals(response.getActionRequired())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancelPayment(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {
        log.info("BFF: Received cancel request for txn {}: reason={}", id, reason);
        CancelPaymentResponse response = transactionClient.cancelPayment(id, reason, authHeader);
        log.info("BFF: Received cancel response for txn {}: status={}", id, response != null ? response.getStatus() : null);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(
            @PathVariable("id") String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        PaymentResponse response = transactionClient.getPayment(id, authHeader);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<List<PaymentResponse>> getPaymentHistory(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        List<PaymentResponse> history = transactionClient.getPaymentHistory(authHeader);
        return ResponseEntity.ok(history);
    }
}

