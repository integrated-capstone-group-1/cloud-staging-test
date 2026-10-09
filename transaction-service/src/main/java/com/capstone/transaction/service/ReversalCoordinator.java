package com.capstone.transaction.service;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.client.AccountsServiceClient;
import com.capstone.transaction.client.CoreBankingClient;
import com.capstone.transaction.dto.ReversalRequest;
import com.capstone.transaction.dto.ReversalResponse;
import com.capstone.transaction.entity.oracle.AdminApproval;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.exception.ReversalInsufficientFundsException;
import com.capstone.transaction.exception.TxnAlreadyReversedException;
import com.capstone.transaction.repository.oracle.AdminApprovalRepository;
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
 * Administrative reversal coordinator with compensating core settlement and 4-eyes maker-checker.
 * DIG-87, ADR-002 / T-001.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReversalCoordinator {

    private final TransactionMasterRepository txnMasterRepository;
    private final CoreBankingClient coreBankingClient;
    private final AccountsServiceClient accountsServiceClient;
    private final AdminApprovalRepository adminApprovalRepository;
    private final LifecycleService lifecycleService;

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
        if (originalTxn.getReversedByTxnId() != null || "REVERSED".equals(originalTxn.getTxnStatus())) {
            throw new TxnAlreadyReversedException(
                    "Transaction " + originalTxnId + " has already been reversed by " + originalTxn.getReversedByTxnId());
        }

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

        String reversalTxnId = UUID.randomUUID().toString();

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

        // Record reversal linkage on original transaction (Delta 3)
        originalTxn.setTxnStatus("REVERSED");
        originalTxn.setStage("REVERSED");
        originalTxn.setReversedByTxnId(reversalTxnId);
        originalTxn.setUpdatedAt(LocalDateTime.now());
        txnMasterRepository.save(originalTxn);

        if (lifecycleService != null) {
            lifecycleService.emitLifecycleEvent(reversalTxn, "POSTED", "COMPLETED", "Reversal of " + originalTxnId);
            lifecycleService.emitLifecycleEvent(originalTxn, "REVERSED", "REVERSED", "Reversed by " + reversalTxnId);
        }

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

    /**
     * Maker proposes a reversal: creates a PENDING AdminApproval record (Delta 3).
     */
    @Transactional("oracleTransactionManager")
    public AdminApproval proposeReversal(String originalTxnId, String makerId, String reason) {
        TransactionMaster originalTxn = txnMasterRepository.findById(originalTxnId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + originalTxnId + " not found"));

        if (!"POSTED".equals(originalTxn.getTxnStatus())) {
            throw new IllegalArgumentException("Only POSTED transactions can be proposed for reversal. Current status: " + originalTxn.getTxnStatus());
        }

        if (originalTxn.getReversedByTxnId() != null || "REVERSED".equals(originalTxn.getTxnStatus())) {
            throw new TxnAlreadyReversedException("Transaction " + originalTxnId + " has already been reversed");
        }

        if (adminApprovalRepository != null) {
            Optional<AdminApproval> pending = adminApprovalRepository.findByTargetTypeAndTargetIdAndStatus("REVERSAL", originalTxnId, "PENDING");
            if (pending.isPresent()) {
                throw new IllegalStateException("A pending reversal approval already exists for transaction " + originalTxnId);
            }
        }

        AdminApproval approval = AdminApproval.builder()
                .approvalId("appr-" + UUID.randomUUID())
                .targetType("REVERSAL")
                .targetId(originalTxnId)
                .makerId(makerId != null ? makerId : "admin-maker")
                .status("PENDING")
                .reason(reason != null ? reason : "Administrative reversal proposed")
                .createdAt(LocalDateTime.now())
                .build();

        log.info("Maker {} proposed reversal for txn {}: approvalId={}", makerId, originalTxnId, approval.getApprovalId());
        return adminApprovalRepository != null ? adminApprovalRepository.save(approval) : approval;
    }

    /**
     * Checker approves and executes the reversal: enforces 4-eyes rule (checker != maker) (Delta 3).
     */
    @Transactional("oracleTransactionManager")
    public ReversalResponse approveReversal(String approvalId, String checkerId) {
        if (adminApprovalRepository == null) {
            throw new IllegalStateException("AdminApproval repository unavailable");
        }

        AdminApproval approval = adminApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("Approval " + approvalId + " not found"));

        if (!"PENDING".equalsIgnoreCase(approval.getStatus())) {
            throw new IllegalStateException("Approval " + approvalId + " is not in PENDING status: " + approval.getStatus());
        }

        // 4-eyes rule enforcement: checker cannot be the maker
        if (checkerId != null && checkerId.equalsIgnoreCase(approval.getMakerId())) {
            throw new IllegalArgumentException("Maker-checker violation: checker cannot be the same as maker (4-eyes rule)");
        }

        log.info("Checker {} approving reversal {} for target txn {}", checkerId, approvalId, approval.getTargetId());
        ReversalResponse response = reverseTransaction(approval.getTargetId(), new ReversalRequest(approval.getReason()));

        approval.setStatus("APPROVED");
        approval.setCheckerId(checkerId != null ? checkerId : "admin-checker");
        approval.setDecidedAt(LocalDateTime.now());
        adminApprovalRepository.save(approval);

        return response;
    }

    /**
     * Checker rejects the reversal request (Delta 3).
     */
    @Transactional("oracleTransactionManager")
    public AdminApproval rejectReversal(String approvalId, String checkerId, String reason) {
        if (adminApprovalRepository == null) {
            throw new IllegalStateException("AdminApproval repository unavailable");
        }

        AdminApproval approval = adminApprovalRepository.findById(approvalId)
                .orElseThrow(() -> new ResourceNotFoundException("Approval " + approvalId + " not found"));

        if (!"PENDING".equalsIgnoreCase(approval.getStatus())) {
            throw new IllegalStateException("Approval " + approvalId + " is not in PENDING status: " + approval.getStatus());
        }

        if (checkerId != null && checkerId.equalsIgnoreCase(approval.getMakerId())) {
            throw new IllegalArgumentException("Maker-checker violation: checker cannot be the same as maker (4-eyes rule)");
        }

        approval.setStatus("REJECTED");
        approval.setCheckerId(checkerId != null ? checkerId : "admin-checker");
        approval.setDecidedAt(LocalDateTime.now());
        if (reason != null) {
            approval.setReason(reason);
        }
        return adminApprovalRepository.save(approval);
    }
}
