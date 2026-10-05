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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionLifecycleTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

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
}
