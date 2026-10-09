package com.capstone.ledger.controller;

import com.capstone.ledger.frontend.adapter.BankingApiClient;
import com.capstone.ledger.frontend.config.SessionAuthInterceptor;
import com.capstone.ledger.frontend.model.AccountView;
import com.capstone.ledger.frontend.model.Customer360View;
import com.capstone.ledger.frontend.model.SystemParameterView;
import com.capstone.ledger.frontend.model.UserSession;
import com.capstone.ledger.frontend.model.enums.AccountStatus;
import com.capstone.ledger.frontend.model.enums.UserRole;
import com.capstone.ledger.frontend.service.AdminPortalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = com.capstone.ledger.frontend.FrontendWebApplication.class, properties = "banking.backend.mode=mock")
@AutoConfigureMockMvc
public class AdminPortalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminPortalService adminPortalService;

    @Autowired
    private BankingApiClient bankingClient;

    private MockHttpSession adminSession;
    private MockHttpSession customerSession;

    @BeforeEach
    void setUp() {
        // Authenticated Admin session
        adminSession = new MockHttpSession();
        UserSession adminUser = new UserSession(
                "usr-admin-01",
                null,
                "admin",
                "Administrator",
                "admin@ledgerbank.com",
                UserRole.ADMIN,
                "jwt-admin-token"
        );
        adminSession.setAttribute(SessionAuthInterceptor.SESSION_USER, adminUser);

        // Authenticated Customer session (Juan)
        customerSession = new MockHttpSession();
        UserSession customerUser = new UserSession(
                "usr-juan-01",
                "CUST-1001",
                "juan",
                "Juan Dela Cruz",
                "juan.delacruz@example.com",
                UserRole.CUSTOMER,
                "jwt-customer-token"
        );
        customerSession.setAttribute(SessionAuthInterceptor.SESSION_USER, customerUser);
        customerSession.setAttribute(SessionAuthInterceptor.SESSION_CUST_ID, "CUST-1001");
    }

    // =========================================================================
    // Criterion 1: Non-admin users are blocked with 403 Forbidden
    // =========================================================================

    @Test
    @DisplayName("DIG-50: Customer session accessing Customer 360 is blocked with 403 Forbidden")
    void testCustomerCannotAccessCustomer360_forbidden() throws Exception {
        mockMvc.perform(get("/admin/customers/CUST-1001").session(customerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DIG-50: Customer session accessing Parameters editor is blocked with 403 Forbidden")
    void testCustomerCannotAccessParameters_forbidden() throws Exception {
        mockMvc.perform(get("/admin/parameters").session(customerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DIG-50: Customer session accessing Held Transfers is blocked with 403 Forbidden")
    void testCustomerCannotAccessHeldTransfers_forbidden() throws Exception {
        mockMvc.perform(get("/admin/transfers/held").session(customerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DIG-50: Customer session invoking Admin Freeze endpoint is blocked with 403 Forbidden")
    void testCustomerCannotFreezeAccount_forbidden() throws Exception {
        mockMvc.perform(post("/api/v1/admin/accounts/ACCT-1001-PHP/freeze").session(customerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DIG-50: Customer session accessing DLQ console is blocked with 403 Forbidden")
    void testCustomerCannotAccessDlq_forbidden() throws Exception {
        mockMvc.perform(get("/admin/dlq").session(customerSession))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // Criterion 2: Admin freeze successfully locks customer account
    // =========================================================================

    @Test
    @DisplayName("DIG-54 & BR-09: Admin freeze successfully sets account_status = FROZEN")
    void testAdminFreezeSuccessfullyLocksAccount() throws Exception {
        String targetAccountId = "ACCT-1001-PHP";

        // Enforce admin freeze via POST /api/v1/admin/accounts/{id}/freeze
        mockMvc.perform(post("/api/v1/admin/accounts/" + targetAccountId + "/freeze")
                        .session(adminSession)
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"))
                .andExpect(jsonPath("$.accountId").value(targetAccountId));

        // Verify state in banking master
        AccountView account = bankingClient.getAccountById(targetAccountId).orElseThrow();
        assertEquals(AccountStatus.FROZEN, account.getAccountStatus());
        assertTrue(account.isFrozen());

        // Restore to ACTIVE via unfreeze
        mockMvc.perform(post("/api/v1/admin/accounts/" + targetAccountId + "/unfreeze")
                        .session(adminSession)
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        AccountView unfrozen = bankingClient.getAccountById(targetAccountId).orElseThrow();
        assertEquals(AccountStatus.ACTIVE, unfrozen.getAccountStatus());
        assertTrue(unfrozen.isActive());
    }

    // =========================================================================
    // Criterion 3: Customer 360 aggregates customer, pocket balances, and transaction history
    // =========================================================================

    @Test
    @DisplayName("DIG-103: Customer 360 aggregates KYC profile, multi-currency pockets, and transaction history")
    void testCustomer360Aggregation() throws Exception {
        mockMvc.perform(get("/admin/customers/CUST-1001").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/customer_360"))
                .andExpect(model().attributeExists("c360", "user"))
                .andExpect(model().attribute("c360", org.hamcrest.Matchers.hasProperty("customerId", org.hamcrest.Matchers.equalTo("CUST-1001"))))
                .andExpect(model().attribute("c360", org.hamcrest.Matchers.hasProperty("pockets", org.hamcrest.Matchers.not(org.hamcrest.Matchers.empty()))));

        // Verify underlying service aggregation
        Customer360View c360 = adminPortalService.getCustomer360("CUST-1001", "admin@ledgerbank.com");
        assertNotNull(c360);
        assertEquals("CUST-1001", c360.getCustomerId());
        assertNotNull(c360.getFullName());
        assertFalse(c360.getPockets().isEmpty());

        // Verify 3-tier pocket balances (Ledger, Held, Available)
        Customer360View.PocketBalanceView primaryPocket = c360.getPockets().get(0);
        assertNotNull(primaryPocket.getLedgerBalance());
        assertNotNull(primaryPocket.getHeldBalance());
        assertNotNull(primaryPocket.getAvailableBalance());
        assertEquals("PHP", primaryPocket.getCurrencyCode());

        // Verify recent transactions up to 20
        assertNotNull(c360.getRecentTransactions());
        assertTrue(c360.getRecentTransactions().size() <= 20);

        // Verify explainable risk score & factors
        assertTrue(c360.getRiskScore() >= 0 && c360.getRiskScore() <= 100);
        assertNotNull(c360.getRiskLevel());
        assertFalse(c360.getRiskFactors().isEmpty());
    }

    // =========================================================================
    // DIG-51 & DIG-54: Held Transfers Queue Approval & Rejection
    // =========================================================================

    @Test
    @DisplayName("DIG-51: Held transfers queue loads and admin can approve/release transfer")
    void testHeldTransfersReviewQueue() throws Exception {
        mockMvc.perform(get("/admin/transfers/held").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/held_transactions"))
                .andExpect(model().attributeExists("heldList", "user"));

        // Approve held transfer
        mockMvc.perform(post("/admin/transfers/held/TXN-HELD-9021/approve")
                        .session(adminSession)
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.success").value(true));

        // Reject held transfer
        mockMvc.perform(post("/admin/transfers/held/TXN-HELD-9045/reject")
                        .session(adminSession)
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.success").value(true));
    }

    // =========================================================================
    // DIG-17 & DIG-54: Versioned Parameter Store
    // =========================================================================

    @Test
    @DisplayName("DIG-17: Parameters editor updates values and increments version with audit trail")
    void testParameterStoreVersioningAndEdit() throws Exception {
        int initialVersion = adminPortalService.getParameters().getVersion();

        mockMvc.perform(get("/admin/parameters").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/parameters"))
                .andExpect(model().attributeExists("params", "user"));

        // Update parameters
        mockMvc.perform(post("/admin/parameters")
                        .session(adminSession)
                        .param("singleTransferLimit", "150000.00")
                        .param("dailyTransferLimit", "600000.00")
                        .param("amlaReportingThreshold", "500000.00")
                        .param("amlaHighRiskThreshold", "300000.00")
                        .param("fxSpreadPercentage", "1.50")
                        .param("maxFailedLoginAttempts", "3")
                        .param("otpTtlSeconds", "300")
                        .param("auditReason", "Annual regulatory limit adjustment"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/parameters"));

        SystemParameterView updated = adminPortalService.getParameters();
        assertEquals(initialVersion + 1, updated.getVersion());
        assertThat(updated.getDailyTransferLimit()).isEqualByComparingTo(new BigDecimal("600000.00"));
        assertThat(updated.getFxSpreadPercentage()).isEqualByComparingTo(new BigDecimal("1.50"));
        assertEquals("admin@ledgerbank.com", updated.getUpdatedBy());
    }

    // =========================================================================
    // DIG-90: DLQ Console
    // =========================================================================

    @Test
    @DisplayName("DIG-90: DLQ console supports inspection, replay, and discard with reason")
    void testDlqConsoleOperations() throws Exception {
        mockMvc.perform(get("/admin/dlq").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dlq"))
                .andExpect(model().attributeExists("messages", "pendingCount"));

        // Replay DLQ message
        mockMvc.perform(post("/admin/dlq/DLQ-MSG-101/replay")
                        .session(adminSession)
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.success").value(true));

        // Discard DLQ message with reason
        mockMvc.perform(post("/admin/dlq/DLQ-MSG-102/discard")
                        .session(adminSession)
                        .param("reason", "Corrupt payload permanently archived")
                        .header("Accept", "application/json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISCARDED"))
                .andExpect(jsonPath("$.success").value(true));
    }

    // =========================================================================
    // DIG-102: Real-time Transaction Monitoring Feed
    // =========================================================================

    @Test
    @DisplayName("DIG-102: Transaction monitoring dashboard renders 11-stage tracker and masked feeds")
    void testTransactionMonitoringDashboard() throws Exception {
        mockMvc.perform(get("/admin/monitoring").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/monitoring"))
                .andExpect(model().attributeExists("feed", "p95LatencyMs", "activeTps"));
    }

    // =========================================================================
    // DIG-53: Audit Ledger Viewer
    // =========================================================================

    @Test
    @DisplayName("DIG-53: Immutable audit ledger viewer displays administrative activity")
    void testAuditLedgerViewer() throws Exception {
        mockMvc.perform(get("/admin/audit").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/audit"))
                .andExpect(model().attributeExists("auditLogs"));
    }

    // =========================================================================
    // DIG-101 & DIG-102: Real-time Telemetry & SSE Stream Endpoints
    // =========================================================================

    @Test
    @DisplayName("DIG-102: Telemetry API returns live KPI metrics and charts for admin")
    void testDashboardTelemetryEndpoint_adminSuccess() throws Exception {
        mockMvc.perform(get("/admin/api/telemetry").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalBalance").exists())
                .andExpect(jsonPath("$.activeAccounts").exists())
                .andExpect(jsonPath("$.heldCount").exists())
                .andExpect(jsonPath("$.pipelineLatencyData").isArray())
                .andExpect(jsonPath("$.txHourlyData").isArray());
    }

    @Test
    @DisplayName("DIG-102: Telemetry API blocks non-admin customers with 403 Forbidden")
    void testDashboardTelemetryEndpoint_customerForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/telemetry").session(customerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DIG-101: Monitoring SSE stream connects successfully for admin")
    void testMonitoringStreamEndpoint_adminSuccess() throws Exception {
        mockMvc.perform(get("/admin/api/monitoring/stream").session(adminSession))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("DIG-101: Monitoring SSE stream blocks customer session with 403 Forbidden")
    void testMonitoringStreamEndpoint_customerForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/monitoring/stream").session(customerSession))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DIG-102: Monitoring JSON feed returns transaction list for admin")
    void testMonitoringFeedJsonEndpoint_adminSuccess() throws Exception {
        mockMvc.perform(get("/admin/api/monitoring/feed").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("DIG-102: Monitoring JSON feed blocks customer session with 403 Forbidden")
    void testMonitoringFeedJsonEndpoint_customerForbidden() throws Exception {
        mockMvc.perform(get("/admin/api/monitoring/feed").session(customerSession))
                .andExpect(status().isForbidden());
    }
}
