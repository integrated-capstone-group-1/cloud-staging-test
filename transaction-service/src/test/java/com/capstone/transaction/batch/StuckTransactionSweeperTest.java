package com.capstone.transaction.batch;

import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.client.CoreBankingClient;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import com.capstone.transaction.service.LifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StuckTransactionSweeperTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Mock
    private CoreBankingClient coreBankingClient;

    @Mock
    private LifecycleService lifecycleService;

    @InjectMocks
    private StuckTransactionSweeper sweeper;

    @BeforeEach
    void setUp() {
        sweeper.setStuckThresholdSeconds(60);
    }

    @Test
    @DisplayName("REG-ROH-05: Resolves stuck transaction as POSTED when CBS confirms settlement")
    void shouldResolveStuckTransactionAsPostedWhenCbsConfirmsSettlement() {
        TransactionMaster stuckTxn = TransactionMaster.builder()
                .txnId("txn-stuck-001")
                .customerId("cust-001")
                .debitAccountId("acct-001")
                .creditAccountId("acct-002")
                .mutationAmount(new BigDecimal("150.0000"))
                .currencyCode("PHP")
                .txnStatus("PROCESSING")
                .stage("SETTLING")
                .initiatedAt(LocalDateTime.now().minusMinutes(5))
                .build();

        when(txnMasterRepository.findByTxnStatusAndInitiatedAtBefore(eq("PROCESSING"), any(LocalDateTime.class)))
                .thenReturn(List.of(stuckTxn));

        when(coreBankingClient.getSettlementStatus("txn-stuck-001"))
                .thenReturn(new CoreSettlementResponse("txn-stuck-001", "FT2627809999", "SETTLED", LocalDateTime.now()));

        int resolved = sweeper.sweepStuckTransactions();

        assertThat(resolved).isEqualTo(1);
        verify(lifecycleService).markPosted("txn-stuck-001", "FT2627809999");
        verify(lifecycleService, never()).markFailed(anyString(), anyString());
    }

    @Test
    @DisplayName("REG-ROH-05: Marks stuck transaction as FAILED when CBS has no record (404)")
    void shouldMarkStuckTransactionFailedWhenCbsHasNoRecord() {
        TransactionMaster stuckTxn = TransactionMaster.builder()
                .txnId("txn-stuck-002")
                .customerId("cust-002")
                .debitAccountId("acct-003")
                .creditAccountId("acct-004")
                .mutationAmount(new BigDecimal("200.0000"))
                .currencyCode("EUR")
                .txnStatus("PROCESSING")
                .stage("SETTLING")
                .initiatedAt(LocalDateTime.now().minusMinutes(10))
                .build();

        when(txnMasterRepository.findByTxnStatusAndInitiatedAtBefore(eq("PROCESSING"), any(LocalDateTime.class)))
                .thenReturn(List.of(stuckTxn));

        when(coreBankingClient.getSettlementStatus("txn-stuck-002"))
                .thenThrow(new ResourceNotFoundException("Settlement not found in CBS"));

        int resolved = sweeper.sweepStuckTransactions();

        assertThat(resolved).isEqualTo(1);
        verify(lifecycleService).markFailed(eq("txn-stuck-002"), contains("Settlement not found in CBS"));
        verify(lifecycleService, never()).markPosted(anyString(), anyString());
    }

    @Test
    @DisplayName("REG-ROH-05: No action when no stuck transactions are present")
    void shouldDoNothingWhenNoStuckTransactionsExist() {
        when(txnMasterRepository.findByTxnStatusAndInitiatedAtBefore(eq("PROCESSING"), any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        int resolved = sweeper.sweepStuckTransactions();

        assertThat(resolved).isZero();
        verifyNoInteractions(coreBankingClient);
        verifyNoInteractions(lifecycleService);
    }
}

