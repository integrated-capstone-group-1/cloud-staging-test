package com.capstone.transaction.controller;

import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.common.security.SecurityUtils;
import com.capstone.transaction.dto.CancelPaymentResponse;
import com.capstone.transaction.dto.PaymentRequest;
import com.capstone.transaction.dto.PaymentResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import com.capstone.transaction.service.LifecycleService;
import com.capstone.transaction.service.PaymentOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller exposing payment orchestration, cancellation, and status lookup endpoints.
 * DIG-15, DIG-18, DIG-83 / T-001.
 */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentController {

    private final PaymentOrchestrator paymentOrchestrator;
    private final LifecycleService lifecycleService;
    private final TransactionMasterRepository txnMasterRepository;

    @PostMapping
    public ResponseEntity<PaymentResponse> makePayment(
            @Valid @RequestBody PaymentRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {

        String customerId = SecurityUtils.getCurrentCustomerId().orElse(null);
        PaymentResponse response = paymentOrchestrator.processPayment(request, idempotencyKey, customerId);

        if ("RESERVED".equals(response.getStatus()) || "STEP_UP_OTP".equals(response.getActionRequired())) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }

        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<CancelPaymentResponse> cancelPayment(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason) {

        TransactionMaster cancelled = lifecycleService.cancelPayment(id, reason);

        return ResponseEntity.ok(CancelPaymentResponse.builder()
                .transactionId(cancelled.getTxnId())
                .status(cancelled.getTxnStatus())
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<PaymentResponse> getPaymentStatus(@PathVariable("id") String id) {
        TransactionMaster txn = txnMasterRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Payment " + id + " not found"));

        PaymentResponse response = PaymentResponse.builder()
                .transactionId(txn.getTxnId())
                .coreReferenceId(txn.getCoreReferenceId())
                .status(txn.getTxnStatus())
                .stage(txn.getStage())
                .paidAmount(txn.getMutationAmount())
                .paidCurrency(txn.getCurrencyCode())
                .createdAt(txn.getCreatedAt())
                .build();

        return ResponseEntity.ok(response);
    }

    @GetMapping("/history")
    public ResponseEntity<List<PaymentResponse>> getPaymentHistory() {
        String customerId = SecurityUtils.getCurrentCustomerId().orElse("cust-user-001");
        if (customerId == null || customerId.isBlank()) {
            customerId = "cust-user-001";
        }

        List<PaymentResponse> history = txnMasterRepository.findByCustomerIdOrderByCreatedAtDesc(customerId)
                .stream()
                .map(txn -> PaymentResponse.builder()
                        .transactionId(txn.getTxnId())
                        .coreReferenceId(txn.getCoreReferenceId())
                        .status(txn.getTxnStatus())
                        .stage(txn.getStage())
                        .paidAmount(txn.getMutationAmount())
                        .paidCurrency(txn.getCurrencyCode())
                        .createdAt(txn.getCreatedAt())
                        .build())
                .toList();

        return ResponseEntity.ok(history);
    }
}
