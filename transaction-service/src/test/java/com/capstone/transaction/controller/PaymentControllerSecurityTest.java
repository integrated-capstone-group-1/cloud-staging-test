package com.capstone.transaction.controller;

import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.dto.CancelPaymentResponse;
import com.capstone.transaction.dto.PaymentResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import com.capstone.transaction.service.LifecycleService;
import com.capstone.transaction.service.PaymentOrchestrator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentControllerSecurityTest {

    @Mock
    private PaymentOrchestrator paymentOrchestrator;

    @Mock
    private LifecycleService lifecycleService;

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @InjectMocks
    private PaymentController paymentController;

    private static final String OWNER_CUSTOMER_ID = "cust-owner-001";
    private static final String ATTACKER_CUSTOMER_ID = "cust-attacker-999";
    private static final String TXN_ID = "txn-100-uuid";

    private TransactionMaster sampleTxn;

    @BeforeEach
    void setUp() {
        sampleTxn = TransactionMaster.builder()
                .txnId(TXN_ID)
                .customerId(OWNER_CUSTOMER_ID)
                .mutationAmount(new BigDecimal("100.0000"))
                .currencyCode("PHP")
                .txnStatus("POSTED")
                .stage("COMPLETED")
                .coreReferenceId("FT-12345")
                .createdAt(LocalDateTime.now())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String customerId, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                customerId,
                null,
                List.of(new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("F-005: Legitimate owner can query their own payment status (200 OK)")
    void shouldAllowOwnerToViewPaymentStatus() {
        authenticateAs(OWNER_CUSTOMER_ID, "CUSTOMER");
        when(txnMasterRepository.findById(TXN_ID)).thenReturn(Optional.of(sampleTxn));

        ResponseEntity<PaymentResponse> response = paymentController.getPaymentStatus(TXN_ID);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTransactionId()).isEqualTo(TXN_ID);
        assertThat(response.getBody().getStatus()).isEqualTo("POSTED");
    }

    @Test
    @DisplayName("F-005: Cross-customer IDOR query is rejected with AccessDeniedException (403)")
    void shouldRejectCrossCustomerViewPaymentStatusWithAccessDenied() {
        authenticateAs(ATTACKER_CUSTOMER_ID, "CUSTOMER");
        when(txnMasterRepository.findById(TXN_ID)).thenReturn(Optional.of(sampleTxn));

        assertThatThrownBy(() -> paymentController.getPaymentStatus(TXN_ID))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("F-005: Admin caller can view any payment status (200 OK)")
    void shouldAllowAdminToViewPaymentStatus() {
        authenticateAs("admin-user", "ADMIN");
        when(txnMasterRepository.findById(TXN_ID)).thenReturn(Optional.of(sampleTxn));

        ResponseEntity<PaymentResponse> response = paymentController.getPaymentStatus(TXN_ID);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTransactionId()).isEqualTo(TXN_ID);
    }

    @Test
    @DisplayName("F-005: Cross-customer cancellation is rejected with AccessDeniedException (403)")
    void shouldRejectCrossCustomerCancelPaymentWithAccessDenied() {
        authenticateAs(ATTACKER_CUSTOMER_ID, "CUSTOMER");
        when(txnMasterRepository.findById(TXN_ID)).thenReturn(Optional.of(sampleTxn));

        assertThatThrownBy(() -> paymentController.cancelPayment(TXN_ID, "Malicious cancel"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verifyNoInteractions(lifecycleService);
    }
}

