package com.capstone.orchestrator.dto;

import com.capstone.common.dto.AccountDTO;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Composite Aggregation DTO for the Home Dashboard.
 *
 * [W-H-Y MODEL]:
 * - WHAT: Aggregated view combining multi-currency pockets, recent transactions, and notification alerts.
 * - HOW: Orchestrator fans out read calls to Accounts Service (:8083), Transaction Service (:8084),
 *        and Notification Service (:8085), merging the results into a single payload.
 * - WHY: BFF Pattern minimizes client-to-backend roundtrips for mobile apps over cellular networks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardHomeResponse {

    @JsonProperty("customer_id")
    private String customerId;

    @JsonProperty("accounts")
    private List<AccountDTO> accounts;

    @JsonProperty("recent_transactions")
    private List<PaymentResponse> recentTransactions;

    @JsonProperty("unread_notifications")
    private List<Object> unreadNotifications;
}

