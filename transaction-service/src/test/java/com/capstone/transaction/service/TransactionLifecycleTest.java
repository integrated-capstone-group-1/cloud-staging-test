package com.capstone.transaction.service;

import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.exception.StateConflictException;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import com.capstone.common.event.TransactionLifecycleEvent;
import com.capstone.transaction.kafka.TransactionEventProducer;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionLifecycleTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Mock
    private com.capstone.transaction.client.AccountsServiceClient accountsServiceClient;

    @Mock
    private TransactionEventProducer transactionEventProducer;

    @InjectMocks
    private LifecycleService lifecycleService;

    @Test
    @DisplayName("CAS Claim transitions AUTHORIZED to PROCESSING (SETTLING)")
    void shouldClaimAuthorizedForSettlement() {
        when(txnMasterRepository.updateStatusCas(
                eq("txn-101"),
                eq("AUTHORIZED"),
                eq("PROCESSING"),
                eq("SETTLING"),
                any(LocalDateTime.class)
        )).thenReturn(1);

        boolean claimed = lifecycleService.claimForSettlement("txn-101");

        assertThat(claimed).isTrue();
        verify(txnMasterRepository).updateStatusCas(eq("txn-101"), eq("AUTHORIZED"), eq("PROCESSING"), eq("SETTLING"), any());
    }

    @Test
    @DisplayName("Verification 3: CAS claim prevents cancellation once in PROCESSING")
    void shouldPreventCancellationOnceInProcessing() {
        TransactionMaster processingTxn = TransactionMaster.builder()
                .txnId("txn-101")
                .txnStatus("PROCESSING")
                .stage("SETTLING")
                .build();

        when(txnMasterRepository.findById("txn-101")).thenReturn(Optional.of(processingTxn));

        assertThatThrownBy(() -> lifecycleService.cancelPayment("txn-101", "Changed my mind"))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("Transaction already in PROCESSING and cannot be cancelled");

        verify(txnMasterRepository, never()).cancelTransactionCas(any(), any(), any());
    }

    @Test
    @DisplayName("Customer cancellation succeeds when transaction is AUTHORIZED")
    void shouldCancelWhenAuthorized() {
        TransactionMaster authorizedTxn = TransactionMaster.builder()
                .txnId("txn-102")
                .txnStatus("AUTHORIZED")
                .stage("AUTH_CONFIRMED")
                .build();

        when(txnMasterRepository.findById("txn-102")).thenReturn(Optional.of(authorizedTxn));
        when(txnMasterRepository.cancelTransactionCas(eq("txn-102"), any(), any(LocalDateTime.class))).thenReturn(1);

        TransactionMaster cancelled = lifecycleService.cancelPayment("txn-102", "Customer requested cancellation");

        assertThat(cancelled.getTxnStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getStage()).isEqualTo("CANCELLED");
        verify(txnMasterRepository).cancelTransactionCas(eq("txn-102"), eq("Customer requested cancellation"), any());
    }

    @Test
    @DisplayName("Customer cancellation fails if CAS race condition won by background processor")
    void shouldFailCancellationIfLostCasRace() {
        TransactionMaster authorizedTxn = TransactionMaster.builder()
                .txnId("txn-103")
                .txnStatus("AUTHORIZED")
                .stage("AUTH_CONFIRMED")
                .build();

        TransactionMaster claimedTxn = TransactionMaster.builder()
                .txnId("txn-103")
                .txnStatus("PROCESSING")
                .stage("SETTLING")
                .build();

        when(txnMasterRepository.findById("txn-103"))
                .thenReturn(Optional.of(authorizedTxn))
                .thenReturn(Optional.of(claimedTxn));

        // CAS update returns 0 because another thread/worker claimed it first
        when(txnMasterRepository.cancelTransactionCas(eq("txn-103"), any(), any(LocalDateTime.class))).thenReturn(0);

        assertThatThrownBy(() -> lifecycleService.cancelPayment("txn-103", "Race test"))
                .isInstanceOf(StateConflictException.class)
                .hasMessageContaining("Transaction already in PROCESSING and cannot be cancelled");
    }

    @Test
    @DisplayName("Delta 1: Customer cancellation in RESERVED status releases CBS hold")
    void shouldCancelWhenReservedAndReleaseHold() {
        TransactionMaster reservedTxn = TransactionMaster.builder()
                .txnId("txn-104")
                .txnStatus("RESERVED")
                .stage("RISK_HELD")
                .debitAccountId("acc-debit-01")
                .coreReferenceId("hold-risk-999")
                .build();

        when(txnMasterRepository.findById("txn-104")).thenReturn(Optional.of(reservedTxn));
        when(txnMasterRepository.cancelTransactionCas(eq("txn-104"), any(), any(LocalDateTime.class))).thenReturn(1);

        TransactionMaster cancelled = lifecycleService.cancelPayment("txn-104", "Customer cancelled risk-held txn");

        assertThat(cancelled.getTxnStatus()).isEqualTo("CANCELLED");
        assertThat(cancelled.getStage()).isEqualTo("CANCELLED");
        verify(txnMasterRepository).cancelTransactionCas(eq("txn-104"), eq("Customer cancelled risk-held txn"), any());
        verify(accountsServiceClient).releaseHold("acc-debit-01", "hold-risk-999");
    }

    @Test
    @DisplayName("Delta 2: Status transition explicitly publishes lifecycle events to Kafka topic txn.lifecycle")
    void shouldPublishLifecycleEventOnStageTransition() {
        TransactionMaster txn = TransactionMaster.builder()
                .txnId("txn-201")
                .customerId("cust-99")
                .debitAccountId("acc-deb")
                .creditAccountId("acc-cred")
                .mutationAmount(new java.math.BigDecimal("150.0000"))
                .currencyCode("PHP")
                .txnStatus("POSTED")
                .stage("COMPLETED")
                .build();

        when(txnMasterRepository.findById("txn-201")).thenReturn(Optional.of(txn));

        lifecycleService.markPosted("txn-201", "core-ref-888");

        verify(txnMasterRepository).markCompleted(eq("txn-201"), eq("POSTED"), eq("COMPLETED"), eq("core-ref-888"), any());
        verify(transactionEventProducer).publishLifecycle(argThat(event ->
                event.txnId().equals("txn-201")
                        && event.txnStatus().equals("POSTED")
                        && event.stage().equals("COMPLETED")
                        && event.amount().compareTo(new java.math.BigDecimal("150.0000")) == 0
        ));
    }
}
