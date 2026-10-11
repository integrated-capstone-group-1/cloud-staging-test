package com.capstone.orchestrator.controller;

import com.capstone.orchestrator.client.TransactionClient;
import com.capstone.orchestrator.dto.PaymentRequest;
import com.capstone.orchestrator.dto.PaymentResponse;
import com.capstone.orchestrator.service.AccountOwnershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentForwardingControllerTest {

    @Mock
    private AccountOwnershipService ownershipService;

    @Mock
    private TransactionClient transactionClient;

    @InjectMocks
    private PaymentForwardingController controller;

    @Test
    @DisplayName("Verifies ownership and forwards payment to Transaction Service successfully")
    void shouldVerifyOwnershipAndForwardPayment() {
        PaymentRequest request = PaymentRequest.builder()
                .senderAccountId("acct-juan-eur-01")
                .recipientAccountId("acct-maria-eur-01")
                .amount(BigDecimal.valueOf(50))
                .currency("EUR")
                .build();

        PaymentResponse mockResponse = PaymentResponse.builder()
                .transactionId("txn-12345")
                .status("POSTED")
                .paidAmount(BigDecimal.valueOf(50))
                .paidCurrency("EUR")
                .build();

        when(transactionClient.forwardPayment(eq(request), eq("idemp-key-1"), eq("Bearer token-abc")))
                .thenReturn(mockResponse);

        ResponseEntity<PaymentResponse> response = controller.makePayment(
                request, "idemp-key-1", "Bearer token-abc");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo("POSTED");

        verify(ownershipService).verifyAccountOwnership("acct-juan-eur-01", "debit payment");
        verify(transactionClient).forwardPayment(request, "idemp-key-1", "Bearer token-abc");
    }

    @Test
    @DisplayName("Aborts before reaching Transaction Service if ownership check fails")
    void shouldAbortWhenOwnershipFails() {
        PaymentRequest request = PaymentRequest.builder()
                .senderAccountId("acct-unauthorized-01")
                .recipientAccountId("acct-target-01")
                .amount(BigDecimal.valueOf(100))
                .currency("PHP")
                .build();

        doThrow(new AccessDeniedException("Access denied: You do not own account acct-unauthorized-01"))
                .when(ownershipService).verifyAccountOwnership("acct-unauthorized-01", "debit payment");

        assertThatThrownBy(() -> controller.makePayment(request, null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verifyNoInteractions(transactionClient);
    }

    @Test
    @DisplayName("Rejects payment when funding_pocket_id is specified without quote_id")
    void shouldRejectWhenFundingPocketMissingQuoteId() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-target-01")
                .amount(BigDecimal.valueOf(100))
                .currency("PHP")
                .fundingPocketId("acct-funding-01")
                .build();

        assertThatThrownBy(() -> controller.makePayment(request, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quote_id is required when funding_pocket_id is specified");

        verifyNoInteractions(transactionClient);
        verifyNoInteractions(ownershipService);
    }

    @Test
    @DisplayName("Verifies funding_pocket_id ownership when quote_id is present")
    void shouldVerifyFundingPocketOwnershipWhenProvided() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-target-01")
                .amount(BigDecimal.valueOf(100))
                .currency("PHP")
                .fundingPocketId("acct-funding-unowned-01")
                .quoteId("q-test-123")
                .build();

        doThrow(new AccessDeniedException("Access denied: You do not own account acct-funding-unowned-01"))
                .when(ownershipService).verifyAccountOwnership("acct-funding-unowned-01", "funding pocket debit");

        assertThatThrownBy(() -> controller.makePayment(request, null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verify(ownershipService).verifyAccountOwnership("acct-funding-unowned-01", "funding pocket debit");
        verifyNoInteractions(transactionClient);
    }

    @Test
    @DisplayName("Throws IllegalStateException when TransactionClient returns null response")
    void shouldHandleNullResponseGracefully() {
        PaymentRequest request = PaymentRequest.builder()
                .senderAccountId("acct-juan-eur-01")
                .recipientAccountId("acct-maria-eur-01")
                .amount(BigDecimal.valueOf(50))
                .currency("EUR")
                .build();

        when(transactionClient.forwardPayment(any(), any(), any())).thenReturn(null);

        assertThatThrownBy(() -> controller.makePayment(request, null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Payment response was null from payment hub");
    }
}

