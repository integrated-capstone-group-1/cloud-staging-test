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

    @Mock
    private com.capstone.transaction.repository.oracle.AdminApprovalRepository adminApprovalRepository;

    @Mock
    private LifecycleService lifecycleService;

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

        // Verify compensating transaction persisted with reversal_of_txn_id and original marked REVERSED with reversed_by_txn_id
        ArgumentCaptor<TransactionMaster> txnCaptor = ArgumentCaptor.forClass(TransactionMaster.class);
        verify(txnMasterRepository, atLeastOnce()).save(txnCaptor.capture());
        java.util.List<TransactionMaster> saved = txnCaptor.getAllValues();

        TransactionMaster savedReversal = saved.stream()
                .filter(t -> "REVERSAL".equals(t.getTxnType()))
                .findFirst()
                .orElseThrow();
        assertThat(savedReversal.getReversalOfTxnId()).isEqualTo("txn-5541-uuid");
        assertThat(savedReversal.getDebitAccountId()).isEqualTo("acct-maria-eur-01");
        assertThat(savedReversal.getCreditAccountId()).isEqualTo("acct-juan-eur-01");

        TransactionMaster savedOriginal = saved.stream()
                .filter(t -> "txn-5541-uuid".equals(t.getTxnId()))
                .findFirst()
                .orElseThrow();
        assertThat(savedOriginal.getTxnStatus()).isEqualTo("REVERSED");
        assertThat(savedOriginal.getReversedByTxnId()).isEqualTo(savedReversal.getTxnId());
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

    @Test
    @DisplayName("Delta 3: 4-Eyes Maker-Checker approval workflow executes reversal successfully")
    void shouldProposeReversalAndApproveWithFourEyesRule() {
        TransactionMaster originalTxn = TransactionMaster.builder()
                .txnId("txn-9911-uuid")
                .customerId("cust-maker-checker")
                .txnType("TRANSFER")
                .debitAccountId("acct-sender")
                .creditAccountId("acct-recipient")
                .mutationAmount(new BigDecimal("100.0000"))
                .currencyCode("PHP")
                .txnStatus("POSTED")
                .stage("COMPLETED")
                .build();

        when(txnMasterRepository.findById("txn-9911-uuid")).thenReturn(Optional.of(originalTxn));
        when(adminApprovalRepository.findByTargetTypeAndTargetIdAndStatus("REVERSAL", "txn-9911-uuid", "PENDING"))
                .thenReturn(Optional.empty());
        when(adminApprovalRepository.save(any(com.capstone.transaction.entity.oracle.AdminApproval.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        // 1. Maker proposes reversal
        com.capstone.transaction.entity.oracle.AdminApproval proposal =
                reversalCoordinator.proposeReversal("txn-9911-uuid", "officer-alice", "Unauthorized debit claim");
        assertThat(proposal.getStatus()).isEqualTo("PENDING");
        assertThat(proposal.getMakerId()).isEqualTo("officer-alice");
        assertThat(proposal.getTargetId()).isEqualTo("txn-9911-uuid");

        // Setup for checker approval
        when(adminApprovalRepository.findById(proposal.getApprovalId())).thenReturn(Optional.of(proposal));
        when(accountsServiceClient.getAccount("acct-recipient"))
                .thenReturn(new AccountDTO("acct-recipient", "cust-recip", "SAVINGS", "ACTIVE", new BigDecimal("500.0000"), "PHP", LocalDateTime.now(), BigDecimal.ZERO, "N"));
        when(coreBankingClient.settle(any()))
                .thenReturn(new CoreSettlementResponse("txn-rev-44", "FT8881", "SETTLED", LocalDateTime.now()));

        // 2. Checker approves reversal (checker != maker)
        ReversalResponse response = reversalCoordinator.approveReversal(proposal.getApprovalId(), "supervisor-bob");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("POSTED");
        assertThat(proposal.getStatus()).isEqualTo("APPROVED");
        assertThat(proposal.getCheckerId()).isEqualTo("supervisor-bob");
        assertThat(proposal.getDecidedAt()).isNotNull();
    }

    @Test
    @DisplayName("Delta 3: Maker cannot self-approve reversal (violates 4-eyes rule)")
    void shouldRejectSelfApprovalUnderFourEyesRule() {
        com.capstone.transaction.entity.oracle.AdminApproval pendingApproval = com.capstone.transaction.entity.oracle.AdminApproval.builder()
                .approvalId("appr-101")
                .targetType("REVERSAL")
                .targetId("txn-9911-uuid")
                .makerId("officer-alice")
                .status("PENDING")
                .build();

        when(adminApprovalRepository.findById("appr-101")).thenReturn(Optional.of(pendingApproval));

        // officer-alice attempts to self-approve
        assertThatThrownBy(() -> reversalCoordinator.approveReversal("appr-101", "officer-alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checker cannot be the same as maker");
    }
}
