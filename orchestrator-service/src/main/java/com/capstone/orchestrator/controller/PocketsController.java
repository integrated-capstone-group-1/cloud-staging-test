package com.capstone.orchestrator.controller;

import com.capstone.common.dto.AccountDTO;
import com.capstone.orchestrator.client.AccountsClient;
import com.capstone.orchestrator.service.AccountOwnershipService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tier 3 Channel Controller for Multi-Currency Pockets.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: Exposes GET /api/v1/accounts/{id}/pockets for customer multi-currency balances (PHP, USD, EUR).
 * - HOW:
 *   1. Verifies caller ownership of account {id} via AccountOwnershipService.
 *   2. Retrieves all multi-currency pockets belonging to the customer from Accounts Service (:8083).
 * - WHY: Decouples core ledger accounts from mobile UI presentation, ensuring customers can only inspect
 *        pockets they legitimately own.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
public class PocketsController {

    private final AccountOwnershipService ownershipService;
    private final AccountsClient accountsClient;

    @GetMapping("/{id}/pockets")
    public ResponseEntity<List<AccountDTO>> getAccountPockets(@PathVariable("id") String id) {
        log.info("BFF: Querying multi-currency pockets for account {}", id);

        // Enforce customer ownership before retrieving account details
        ownershipService.verifyAccountOwnership(id, "view pockets");

        AccountDTO account = accountsClient.getAccount(id);
        List<AccountDTO> pockets = accountsClient.getCustomerPockets(account.customerId());

        return ResponseEntity.ok(pockets);
    }
}

