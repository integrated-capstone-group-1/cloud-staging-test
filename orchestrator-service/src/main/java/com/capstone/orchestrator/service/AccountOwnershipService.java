package com.capstone.orchestrator.service;

import com.capstone.common.dto.AccountDTO;
import com.capstone.common.security.SecurityUtils;
import com.capstone.orchestrator.client.AccountsClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Service enforcing strict customer account ownership at the Channel Layer.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT (Definition): Enforces boundary ownership check (caller can only debit/read their own accounts).
 * - HOW (Mechanism): Inspects SecurityContext for authenticated customer ID. If senderAccountId is specified,
 *                     queries Accounts Service (:8083) to verify that the account's owner matches the token.
 * - WHY (Rationale): Prevents malicious customers from snooping on or attempting debits against other customers'
 *                    accounts before the request ever reaches Transaction Service or CBS.
 *
 * TRACEABILITY RULE:
 * Who (AccountOwnershipService) -> Does What (validates ownership) -> Where (Tier 3 entry)
 * -> Through What (AccountsClient) -> With What Result (allows request or throws 403 AccessDeniedException).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AccountOwnershipService {

    private final AccountsClient accountsClient;

    /**
     * Verifies that the authenticated customer owns the given account ID.
     * Privileged callers (ADMIN, INTERNAL) bypass this check.
     */
    public void verifyAccountOwnership(String accountId, String actionDescription) {
        if (accountId == null || accountId.isBlank()) {
            return;
        }

        if (SecurityUtils.isPrivileged()) {
            log.debug("Privileged caller bypassed ownership check for account {}", accountId);
            return;
        }

        String authenticatedCustomerId = SecurityUtils.getCurrentCustomerId().orElse(null);
        if (authenticatedCustomerId == null || authenticatedCustomerId.isBlank()) {
            throw new AccessDeniedException("Authentication required to " + actionDescription);
        }

        AccountDTO account = accountsClient.getAccount(accountId);
        if (account == null) {
            throw new AccessDeniedException("Account " + accountId + " not found or unauthorized");
        }

        if (!authenticatedCustomerId.equalsIgnoreCase(account.customerId())) {
            log.warn("Security Alert: Customer {} attempted unauthorized {} on account {} owned by {}",
                    authenticatedCustomerId, actionDescription, accountId, account.customerId());
            throw new AccessDeniedException("Access denied: You do not own account " + accountId);
        }

        log.debug("Ownership verified for customer {} on account {}", authenticatedCustomerId, accountId);
    }

    /**
     * Resolves the customer's primary account ID for the specified currency when omitted by caller.
     */
    public String resolveDefaultAccountId(String customerId, String currency) {
        if (customerId == null || customerId.isBlank()) {
            return null;
        }
        try {
            List<AccountDTO> pockets = accountsClient.getCustomerPockets(customerId);
            return pockets.stream()
                    .filter(a -> currency != null && currency.equalsIgnoreCase(a.currencyCode()))
                    .map(AccountDTO::accountId)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            log.warn("Could not resolve default account for customer {}: {}", customerId, ex.getMessage());
            return null;
        }
    }
}

