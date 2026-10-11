package com.capstone.orchestrator.controller;

import com.capstone.common.dto.AccountDTO;
import com.capstone.orchestrator.client.AccountsClient;
import com.capstone.orchestrator.client.NotificationClient;
import com.capstone.orchestrator.client.TransactionClient;
import com.capstone.orchestrator.dto.DashboardHomeResponse;
import com.capstone.orchestrator.dto.PaymentResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

    @Mock
    private AccountsClient accountsClient;

    @Mock
    private TransactionClient transactionClient;

    @Mock
    private NotificationClient notificationClient;

    @InjectMocks
    private DashboardController dashboardController;

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
    @DisplayName("SEAM-ROH-06: Aggregates accounts, transactions, and alerts into unified dashboard")
    void shouldAggregateHomeDashboard() {
        AccountDTO pocketPhp = new AccountDTO("acct-php-01", "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("5000.00"), "PHP", java.time.LocalDateTime.now());
        AccountDTO pocketEur = new AccountDTO("acct-eur-01", "cust-user-101", "SAVINGS", "ACTIVE", new BigDecimal("100.00"), "EUR", java.time.LocalDateTime.now());
        when(accountsClient.getCustomerPockets("cust-user-101")).thenReturn(List.of(pocketPhp, pocketEur));

        PaymentResponse txn1 = PaymentResponse.builder().transactionId("txn-01").status("POSTED").build();
        when(transactionClient.getPaymentHistory(any())).thenReturn(List.of(txn1));

        when(notificationClient.getNotifications(eq("cust-user-101"), any())).thenReturn(List.of("Payment alert"));

        ResponseEntity<DashboardHomeResponse> response = dashboardController.getHomeDashboard("Bearer token-abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        DashboardHomeResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getCustomerId()).isEqualTo("cust-user-101");
        assertThat(body.getAccounts()).hasSize(2);
        assertThat(body.getRecentTransactions()).hasSize(1);
        assertThat(body.getUnreadNotifications()).hasSize(1);

        verify(accountsClient).getCustomerPockets("cust-user-101");
        verify(transactionClient).getPaymentHistory("Bearer token-abc");
        verify(notificationClient).getNotifications("cust-user-101", "Bearer token-abc");
    }

    @Test
    @DisplayName("Throws AccessDeniedException when customer context is missing")
    void shouldRejectWhenUnauthenticated() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> dashboardController.getHomeDashboard(null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Authenticated customer context required");

        verifyNoInteractions(accountsClient, transactionClient, notificationClient);
    }
}