package com.capstone.orchestrator;

import com.capstone.common.dto.AccountDTO;
import com.capstone.orchestrator.client.AccountsClient;
import com.capstone.orchestrator.client.NotificationClient;
import com.capstone.orchestrator.client.TransactionClient;
import com.capstone.orchestrator.dto.DashboardHomeResponse;
import com.capstone.orchestrator.dto.PaymentRequest;
import com.capstone.orchestrator.dto.PaymentResponse;
import com.capstone.orchestrator.service.AccountOwnershipService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration Test: Rohan Channel Orchestrator Seam (SEAM-ROH-05, SEAM-ROH-06, SEAM-ROH-07).
 * Verifies Tier 3 Channel Orchestrator (orchestrator-service :8089):
 * 1. Customer payment journey: verifies account ownership, error maps, forwards to Transaction Service (:8084).
 * 2. Unauthorized payment: immediately rejects with 403, shielding CBS Core Ledger from unauthorized mutations.
 * 3. Home dashboard aggregation: fans out parallel queries across Accounts (:8083), Transactions (:8084), and Notifications (:8085).
 */
@SpringBootTest(classes = OrchestratorApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class ChannelOrchestratorIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccountsClient accountsClient;

    @MockBean
    private TransactionClient transactionClient;

    @MockBean
    private NotificationClient notificationClient;

    @Autowired
    private AccountOwnershipService ownershipService;

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "cust-user-101",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("SEAM-ROH-05: Authenticated customer journey verifies ownership and forwards to Transaction Service")
    void shouldOrchestratePaymentJourneyEndToEnd() throws Exception {
        String senderAccount = "acct-juan-eur-01";
        AccountDTO senderAccountDto = new AccountDTO(
                senderAccount, "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("100.00"), "EUR", java.time.LocalDateTime.now());

        when(accountsClient.getAccount(senderAccount)).thenReturn(senderAccountDto);

        PaymentResponse txResponse = PaymentResponse.builder()
                .transactionId("TXN-ORCH-8888")
                .coreReferenceId("FT2627800001")
                .status("POSTED")
                .paidAmount(new BigDecimal("50.00"))
                .paidCurrency("EUR")
                .build();

        when(transactionClient.forwardPayment(any(PaymentRequest.class), any(), any()))
                .thenReturn(txResponse);

        String paymentPayload = """
                {
                  "sender_account_id": "acct-juan-eur-01",
                  "recipient_account_id": "acct-maria-eur-01",
                  "amount": 50.00,
                  "currency": "EUR"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Idempotency-Key", "idemp-orch-01")
                        .header("Authorization", "Bearer valid-jwt")
                        .content(paymentPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.transaction_id").value("TXN-ORCH-8888"))
                .andExpect(jsonPath("$.core_reference_id").value("FT2627800001"));

        verify(accountsClient).getAccount(senderAccount);
        verify(transactionClient).forwardPayment(any(PaymentRequest.class), eq("idemp-orch-01"), eq("Bearer valid-jwt"));
    }

    @Test
    @DisplayName("SEAM-ROH-05: Unowned account rejected with 403 and never invokes Transaction Service")
    void shouldBlockUnauthorizedPaymentBeforeReachingCoreLedger() throws Exception {
        String unownedAccount = "acct-victim-php-01";
        // Account belongs to cust-victim-999, NOT calling user cust-user-101
        AccountDTO victimAccountDto = new AccountDTO(
                unownedAccount, "cust-victim-999", "SAVINGS", "ACTIVE", new BigDecimal("50000.00"), "PHP", java.time.LocalDateTime.now());

        when(accountsClient.getAccount(unownedAccount)).thenReturn(victimAccountDto);

        String maliciousPayload = """
                {
                  "sender_account_id": "acct-victim-php-01",
                  "recipient_account_id": "acct-attacker-php-01",
                  "amount": 1000.00,
                  "currency": "PHP"
                }
                """;

        mockMvc.perform(post("/api/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(maliciousPayload))
                .andExpect(status().isForbidden());

        // Assert: Transaction Service is NEVER called, protecting ledger mutation
        verifyNoInteractions(transactionClient);
    }

    @Test
    @DisplayName("SEAM-ROH-06: Aggregates dashboard data across Accounts, Transaction, and Notification services")
    void shouldAggregateHomeDashboardAcrossAllThreeServices() throws Exception {
        AccountDTO phpPocket = new AccountDTO("acct-php-01", "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("2500.00"), "PHP", java.time.LocalDateTime.now());
        when(accountsClient.getCustomerPockets("cust-user-101")).thenReturn(List.of(phpPocket));

        PaymentResponse pastTxn = PaymentResponse.builder()
                .transactionId("txn-prev-1")
                .status("POSTED")
                .build();
        when(transactionClient.getPaymentHistory(any())).thenReturn(List.of(pastTxn));

        when(notificationClient.getNotifications(eq("cust-user-101"), any()))
                .thenReturn(List.of("Your monthly statement is ready"));

        mockMvc.perform(get("/api/v1/dashboard/home")
                        .header("Authorization", "Bearer valid-jwt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer_id").value("cust-user-101"))
                .andExpect(jsonPath("$.accounts[0].accountId").value("acct-php-01"))
                .andExpect(jsonPath("$.recent_transactions[0].transaction_id").value("txn-prev-1"))
                .andExpect(jsonPath("$.unread_notifications[0]").value("Your monthly statement is ready"));

        verify(accountsClient).getCustomerPockets("cust-user-101");
        verify(transactionClient).getPaymentHistory("Bearer valid-jwt");
        verify(notificationClient).getNotifications("cust-user-101", "Bearer valid-jwt");
    }

    @Test
    @DisplayName("SEAM-ROH-07: Retrieves multi-currency pockets after verifying caller ownership")
    void shouldRetrieveMultiCurrencyPockets() throws Exception {
        String accountId = "acct-php-01";
        AccountDTO primary = new AccountDTO(accountId, "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("5000.00"), "PHP", java.time.LocalDateTime.now());
        AccountDTO eurPocket = new AccountDTO("acct-eur-01", "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("100.00"), "EUR", java.time.LocalDateTime.now());
        AccountDTO usdPocket = new AccountDTO("acct-usd-01", "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("50.00"), "USD", java.time.LocalDateTime.now());

        when(accountsClient.getAccount(accountId)).thenReturn(primary);
        when(accountsClient.getCustomerPockets("cust-user-101")).thenReturn(List.of(primary, eurPocket, usdPocket));

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/pockets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].currencyCode").value("PHP"))
                .andExpect(jsonPath("$[1].currencyCode").value("EUR"))
                .andExpect(jsonPath("$[2].currencyCode").value("USD"));

        verify(accountsClient, atLeastOnce()).getAccount(accountId);
        verify(accountsClient).getCustomerPockets("cust-user-101");
    }
}