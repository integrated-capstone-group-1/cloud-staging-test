package com.capstone.orchestrator.controller;

import com.capstone.common.dto.AccountDTO;
import com.capstone.common.security.SecurityUtils;
import com.capstone.orchestrator.client.AccountsClient;
import com.capstone.orchestrator.client.NotificationClient;
import com.capstone.orchestrator.client.TransactionClient;
import com.capstone.orchestrator.dto.DashboardHomeResponse;
import com.capstone.orchestrator.dto.PaymentResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Tier 3 Channel Controller for Home Dashboard Aggregation.
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: Aggregation endpoint GET /api/v1/dashboard/home assembling customer home screen state.
 * - HOW: Fans out lightweight queries to Accounts (:8083), Transaction (:8084), and Notification (:8085)
 *        services and packages them into a unified DashboardHomeResponse.
 * - WHY: BFF Pattern reduces mobile network roundtrips from 3 HTTP requests to 1, while isolating
 *        failures (e.g. notification outages don't block display of account balances).
 *
 * TRACE:
 * Mobile Client -> Gateway (:8080) -> DashboardController (:8089)
 *   -> AccountsClient.getCustomerPockets()
 *   -> TransactionClient.getPaymentHistory()
 *   -> NotificationClient.getNotifications()
 *   -> DashboardHomeResponse
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final AccountsClient accountsClient;
    private final TransactionClient transactionClient;
    private final NotificationClient notificationClient;

    @GetMapping("/home")
    public ResponseEntity<DashboardHomeResponse> getHomeDashboard(
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String customerId = SecurityUtils.getCurrentCustomerId().orElse(null);
        if (customerId == null || customerId.isBlank()) {
            throw new AccessDeniedException("Authenticated customer context required for home dashboard");
        }

        log.info("BFF: Assembling home dashboard for customer {}", customerId);

        // 1. Fetch multi-currency pockets from Mock CBS
        List<AccountDTO> accounts = accountsClient.getCustomerPockets(customerId);

        // 2. Fetch recent payments from Transaction Service
        List<PaymentResponse> recentTransactions = transactionClient.getPaymentHistory(authHeader);

        // 3. Fetch unread alerts from Notification Service (graceful fallback on failure)
        List<Object> unreadNotifications = notificationClient.getNotifications(customerId, authHeader);

        DashboardHomeResponse dashboard = DashboardHomeResponse.builder()
                .customerId(customerId)
                .accounts(accounts)
                .recentTransactions(recentTransactions)
                .unreadNotifications(unreadNotifications)
                .build();

        return ResponseEntity.ok(dashboard);
    }
}

