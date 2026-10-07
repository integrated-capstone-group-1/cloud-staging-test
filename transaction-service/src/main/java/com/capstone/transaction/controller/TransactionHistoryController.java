package com.capstone.transaction.controller;

import com.capstone.transaction.dto.PageResponse;
import com.capstone.transaction.dto.TransactionDetailsResponse;
import com.capstone.transaction.service.TransactionHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * Controller exposing transaction status, stage timeline, and filtered history (DIG-18 / T-001).
 *
 * Acceptance Criteria:
 * 1) GET /v1/transactions/{id} returns status + simplified stages
 * 2) GET /v1/transactions?status=&from=&to= paginated
 * 3) Each entry links to its audit trail
 */
@RestController
@RequestMapping({"/v1/transactions", "/api/v1/transactions"})
@RequiredArgsConstructor
@Slf4j
public class TransactionHistoryController {

    private final TransactionHistoryService transactionHistoryService;

    /**
     * AC #1: GET /v1/transactions/{id} returns status + simplified stages and links to audit trail.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionDetailsResponse> getTransactionById(@PathVariable("id") String id) {
        log.info("Fetching transaction details and simplified stages for txnId={}", id);
        TransactionDetailsResponse response = transactionHistoryService.getTransactionById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * AC #2: GET /v1/transactions?status=&from=&to= paginated.
     * AC #3: Each entry links to its audit trail via auditRef & auditTrailUrl.
     */
    @GetMapping
    public ResponseEntity<PageResponse<TransactionDetailsResponse>> getTransactions(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(value = "customerId", required = false) String customerId,
            @RequestParam(value = "accountId", required = false) String accountId,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        log.info("Querying transactions: status={}, from={}, to={}, customerId={}, accountId={}, page={}, size={}",
                status, from, to, customerId, accountId, page, size);

        PageResponse<TransactionDetailsResponse> response = transactionHistoryService.getTransactions(
                status, from, to, customerId, accountId, page, size);

        return ResponseEntity.ok(response);
    }
}
