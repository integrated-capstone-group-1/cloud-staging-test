package com.capstone.transaction.service;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.client.AccountsServiceClient;
import com.capstone.transaction.client.CoreBankingClient;
import com.capstone.transaction.dto.ReversalRequest;
import com.capstone.transaction.dto.ReversalResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.exception.ReversalInsufficientFundsException;
import com.capstone.transaction.exception.TxnAlreadyReversedException;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Administrative reversal coordinator with compensating core settlement.
 * DIG-87, ADR-002 / T-001.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReversalCoordinator {

    private final TransactionMasterRepository txnMasterRepository;
    private final CoreBankingClient coreBankingClient;
    private final AccountsServiceClient accountsServiceClient;

    /**
     * Executes an administrative reversal:
     * 1. Validate original transaction exists and is POSTED
     * 2. Validate it hasn't already been reversed (reversal_of_txn_id uniqueness)
     * 3. Validate recipient has sufficient funds to reverse
     * 4. Invert settlement legs and post compensating transaction to CBS
     * 5. Record compensating transaction with txn_type = 'REVERSAL' linking reversal_of_txn_id
     */
    @Transactional("oracleTransactionManager")
    public ReversalResponse reverseTransaction(String originalTxnId, ReversalRequest request) {
        TransactionMaster originalTxn = txnMasterRepository.findById(originalTxnId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + originalTxnId + " not found"));

        if (!"POSTED".equals(originalTxn.getTxnStatus())) {
            throw new IllegalArgumentException("Only POSTED transactions can be reversed. Current status: " + originalTxn.getTxnStatus());
        }

        // Check if already reversed
        Optional<TransactionMaster> existingReversal = txnMasterRepository.findByReversalOfTxnId(originalTxnId);
        if (existingReversal.isPresent()) {
            throw new TxnAlreadyReversedException(
                    "Transaction " + originalTxnId + " has already been reversed by " + existingReversal.get().getTxnId());
        }

        // Pre-settlement recipient balance verification (if recipient account is known)
        String recipientAccountId = originalTxn.getCreditAccountId();
        if (recipientAccountId != null && accountsServiceClient != null) {
            try {
                var recipientAcct = accountsServiceClient.getAccount(recipientAccountId);
                if (recipientAcct != null && recipientAcct.balanceAmount() != null
                        && recipientAcct.balanceAmount().compareTo(originalTxn.getMutationAmount()) < 0) {
                    throw new ReversalInsufficientFundsException(
                            "Recipient pocket balance is insufficient to refund reversed funds");
                }
            } catch (ReversalInsufficientFundsException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Could not pre-verify recipient balance before reversal: {}", ex.getMessage());
            }
        }

        String reversalTxnId = "txn-rev-" + UUID.randomUUID().toString();

        // Invert settlement legs: original credit account debited, original debit account credited
        List<SettlementLegDto> invertedLegs = List.of(
                new SettlementLegDto(originalTxn.getCreditAccountId(), "DEBIT", originalTxn.getMutationAmount(), originalTxn.getCurrencyCode(), 1),
                new SettlementLegDto(originalTxn.getDebitAccountId(), "CREDIT", originalTxn.getMutationAmount(), originalTxn.getCurrencyCode(), 2)
        );

        CoreSettlementRequest settlementRequest = new CoreSettlementRequest(
                reversalTxnId,
                "REVERSAL",
                null,
                invertedLegs
        );

        log.info("Dispatching compensating reversal settlement for original txn {}", originalTxnId);
        CoreSettlementResponse cbsResponse = coreBankingClient.settle(settlementRequest);

        // Record compensating transaction row in transaction_master
        TransactionMaster reversalTxn = TransactionMaster.builder()
                .txnId(reversalTxnId)
                .customerId(originalTxn.getCustomerId())
                .txnType("REVERSAL")
                .debitAccountId(originalTxn.getCreditAccountId())
                .creditAccountId(originalTxn.getDebitAccountId())
                .mutationAmount(originalTxn.getMutationAmount())
                .currencyCode(originalTxn.getCurrencyCode())
                .txnStatus("POSTED")
                .stage("COMPLETED")
                .coreReferenceId(cbsResponse.coreReferenceId())
                .reversalOfTxnId(originalTxnId)
                .cancelReason(request != null ? request.getReason() : "Admin reversal")
                .initiatedAt(LocalDateTime.now())
                .completedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .createdBy("ADMIN")
                .build();

        txnMasterRepository.save(reversalTxn);

        return ReversalResponse.builder()
                .reversalTxnId(reversalTxnId)
                .originalTxnId(originalTxnId)
                .coreReferenceId(cbsResponse.coreReferenceId())
                .status("POSTED")
                .reversedAmount(originalTxn.getMutationAmount())
                .reversedCurrency(originalTxn.getCurrencyCode())
                .settledAt(LocalDateTime.now())
                .build();
    }
}
