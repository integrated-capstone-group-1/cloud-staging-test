package com.capstone.transaction.service;

import com.capstone.common.dto.AccountDTO;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.transaction.client.AccountsServiceClient;
import com.capstone.transaction.client.CoreBankingClient;
import com.capstone.transaction.dto.ReversalRequest;
import com.capstone.transaction.dto.ReversalResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.exception.ReversalInsufficientFundsException;
import com.capstone.transaction.exception.TxnAlreadyReversedException;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransactionReversalTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Mock
    private CoreBankingClient coreBankingClient;

    @Mock
    private AccountsServiceClient accountsServiceClient;

    @InjectMocks
    private ReversalCoordinator reversalCoordinator;

    @Test
    @DisplayName("Verification 5: Administrative reversal issues compensating core settlement and links reversal_of_txn_id")
    void shouldExecuteCompensatingReversalSuccessfully() {
        TransactionMaster originalTxn = TransactionMaster.builder()
                .txnId("txn-5541-uuid")
                .customerId("cust-user-001")
                .txnType("TRANSFER")
                .debitAccountId("acct-juan-eur-01")
                .creditAccountId("acct-maria-eur-01")
                .mutationAmount(new BigDecimal("50.0000"))
                .currencyCode("EUR")
                .txnStatus("POSTED")
                .stage("COMPLETED")
                .coreReferenceId("FT2627800001")
                .build();

        when(txnMasterRepository.findById("txn-5541-uuid")).thenReturn(Optional.of(originalTxn));
        when(txnMasterRepository.findByReversalOfTxnId("txn-5541-uuid")).thenReturn(Optional.empty());

        when(accountsServiceClient.getAccount("acct-maria-eur-01"))
                .thenReturn(new AccountDTO("acct-maria-eur-01", "cust-maria", "SAVINGS", "ACTIVE", new BigDecimal("100.0000"), "EUR", LocalDateTime.now(), BigDecimal.ZERO, "N"));

        when(coreBankingClient.settle(any()))
                .thenReturn(new CoreSettlementResponse("txn-rev-101", "FT2627800099", "SETTLED", LocalDateTime.now()));

        ReversalResponse response = reversalCoordinator.reverseTransaction("txn-5541-uuid", new ReversalRequest("Compliance adjustment"));

        assertThat(response).isNotNull();
        assertThat(response.getOriginalTxnId()).isEqualTo("txn-5541-uuid");
        assertThat(response.getStatus()).isEqualTo("POSTED");
        assertThat(response.getReversedAmount()).isEqualByComparingTo(new BigDecimal("50.0000"));

        // Verify compensating legs are inverted: maria DEBIT, juan CREDIT
        ArgumentCaptor<CoreSettlementRequest> captor = ArgumentCaptor.forClass(CoreSettlementRequest.class);
        verify(coreBankingClient).settle(captor.capture());
        CoreSettlementRequest settlement = captor.getValue();
        assertThat(settlement.legs()).hasSize(2);
        assertThat(settlement.legs().get(0).accountId()).isEqualTo("acct-maria-eur-01");
        assertThat(settlement.legs().get(0).direction()).isEqualTo("DEBIT");
        assertThat(settlement.legs().get(1).accountId()).isEqualTo("acct-juan-eur-01");
        assertThat(settlement.legs().get(1).direction()).isEqualTo("CREDIT");

        // Verify compensating transaction persisted with reversal_of_txn_id
        ArgumentCaptor<TransactionMaster> txnCaptor = ArgumentCaptor.forClass(TransactionMaster.class);
        verify(txnMasterRepository).save(txnCaptor.capture());
        TransactionMaster savedReversal = txnCaptor.getValue();
        assertThat(savedReversal.getTxnType()).isEqualTo("REVERSAL");
        assertThat(savedReversal.getReversalOfTxnId()).isEqualTo("txn-5541-uuid");
        assertThat(savedReversal.getDebitAccountId()).isEqualTo("acct-maria-eur-01");
        assertThat(savedReversal.getCreditAccountId()).isEqualTo("acct-juan-eur-01");
    }

    @Test
    @DisplayName("Prevents double reversal when transaction has already been reversed")
    void shouldRejectDuplicateReversal() {
        TransactionMaster originalTxn = TransactionMaster.builder()
                .txnId("txn-5541-uuid")
                .txnStatus("POSTED")
                .mutationAmount(new BigDecimal("50.0000"))
                .currencyCode("EUR")
                .build();

        TransactionMaster existingReversal = TransactionMaster.builder()
                .txnId("txn-rev-9011-uuid")
                .reversalOfTxnId("txn-5541-uuid")
                .build();

        when(txnMasterRepository.findById("txn-5541-uuid")).thenReturn(Optional.of(originalTxn));
        when(txnMasterRepository.findByReversalOfTxnId("txn-5541-uuid")).thenReturn(Optional.of(existingReversal));

        assertThatThrownBy(() -> reversalCoordinator.reverseTransaction("txn-5541-uuid", new ReversalRequest("Duplicate attempt")))
                .isInstanceOf(TxnAlreadyReversedException.class)
                .hasMessageContaining("already been reversed");

        verifyNoInteractions(coreBankingClient);
    }

    @Test
    @DisplayName("Rejects reversal if recipient balance is insufficient to refund")
    void shouldRejectReversalIfRecipientHasInsufficientFunds() {
        TransactionMaster originalTxn = TransactionMaster.builder()
                .txnId("txn-5541-uuid")
                .creditAccountId("acct-maria-eur-01")
                .txnStatus("POSTED")
                .mutationAmount(new BigDecimal("50.0000"))
                .currencyCode("EUR")
                .build();

        when(txnMasterRepository.findById("txn-5541-uuid")).thenReturn(Optional.of(originalTxn));
        when(txnMasterRepository.findByReversalOfTxnId("txn-5541-uuid")).thenReturn(Optional.empty());

        // Recipient only has 10 EUR, but needs 50 EUR
        when(accountsServiceClient.getAccount("acct-maria-eur-01"))
                .thenReturn(new AccountDTO("acct-maria-eur-01", "cust-maria", "SAVINGS", "ACTIVE", new BigDecimal("10.0000"), "EUR", LocalDateTime.now(), BigDecimal.ZERO, "N"));

        assertThatThrownBy(() -> reversalCoordinator.reverseTransaction("txn-5541-uuid", new ReversalRequest("Adjustment")))
                .isInstanceOf(ReversalInsufficientFundsException.class)
                .hasMessageContaining("Recipient pocket balance is insufficient");

        verifyNoInteractions(coreBankingClient);
    }
}
