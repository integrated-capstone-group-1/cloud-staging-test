package com.capstone.transaction.service;

import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.common.security.SecurityUtils;
import com.capstone.transaction.dto.PageResponse;
import com.capstone.transaction.dto.StageTimelineDto;
import com.capstone.transaction.dto.TransactionDetailsResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for querying transaction details, simplified stage timeline,
 * and filtered paginated transaction history (DIG-18 / T-001).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TransactionHistoryService {

    private final TransactionMasterRepository txnMasterRepository;

    @Transactional(readOnly = true)
    public TransactionDetailsResponse getTransactionById(String transactionId) {
        TransactionMaster txn = txnMasterRepository.findById(transactionId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction " + transactionId + " not found"));

        if (txn.getCustomerId() != null) {
            SecurityUtils.checkCustomerAccess(txn.getCustomerId(), "view transaction details");
        }

        return mapToResponse(txn);
    }

    @Transactional(readOnly = true)
    public PageResponse<TransactionDetailsResponse> getTransactions(
            String status,
            LocalDateTime from,
            LocalDateTime to,
            String customerId,
            String accountId,
            int page,
            int size) {

        String effectiveCustomerId = customerId;
        if ((effectiveCustomerId == null || effectiveCustomerId.isBlank()) && !SecurityUtils.isPrivileged()) {
            effectiveCustomerId = SecurityUtils.getCurrentCustomerId().orElse(null);
        }

        if (effectiveCustomerId != null && !effectiveCustomerId.isBlank()) {
            SecurityUtils.checkCustomerAccess(effectiveCustomerId, "view transaction history");
        }

        final String filterCustId = effectiveCustomerId;

        Specification<TransactionMaster> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filterCustId != null && !filterCustId.isBlank()) {
                predicates.add(cb.equal(root.get("customerId"), filterCustId));
            }

            if (accountId != null && !accountId.isBlank()) {
                predicates.add(cb.or(
                        cb.equal(root.get("debitAccountId"), accountId),
                        cb.equal(root.get("creditAccountId"), accountId)
                ));
            }

            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(cb.upper(root.get("txnStatus")), status.trim().toUpperCase()));
            }

            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }

            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };

        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.max(size, 1), Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<TransactionMaster> resultPage = txnMasterRepository.findAll(spec, pageable);

        List<TransactionDetailsResponse> items = resultPage.getContent().stream()
                .map(this::mapToResponse)
                .toList();

        return PageResponse.<TransactionDetailsResponse>builder()
                .content(items)
                .page(resultPage.getNumber())
                .size(resultPage.getSize())
                .totalElements(resultPage.getTotalElements())
                .totalPages(resultPage.getTotalPages())
                .first(resultPage.isFirst())
                .last(resultPage.isLast())
                .build();
    }

    public TransactionDetailsResponse mapToResponse(TransactionMaster txn) {
        String auditRef = "/api/v1/ledger/audit/" + txn.getTxnId();

        return TransactionDetailsResponse.builder()
                .transactionId(txn.getTxnId())
                .status(txn.getTxnStatus())
                .stage(txn.getStage())
                .txnType(txn.getTxnType())
                .amount(txn.getMutationAmount())
                .currency(txn.getCurrencyCode())
                .debitAccountId(txn.getDebitAccountId())
                .creditAccountId(txn.getCreditAccountId())
                .customerId(txn.getCustomerId())
                .coreReferenceId(txn.getCoreReferenceId())
                .auditRef(auditRef)
                .auditTrailUrl(auditRef)
                .cancelReason(txn.getCancelReason())
                .reversalOfTxnId(txn.getReversalOfTxnId())
                .isCrossCurrency(txn.getIsCrossCurrency())
                .fxRate(txn.getFxRate())
                .destAmount(txn.getDestAmount())
                .destCurrencyCode(txn.getDestCurrencyCode())
                .createdAt(txn.getCreatedAt())
                .completedAt(txn.getCompletedAt())
                .stages(buildSimplifiedStages(txn))
                .build();
    }

    /**
     * Builds simplified, customer-friendly lifecycle stage progression (DIG-18 AC #1).
     */
    public List<StageTimelineDto> buildSimplifiedStages(TransactionMaster txn) {
        List<StageTimelineDto> stages = new ArrayList<>();
        String status = txn.getTxnStatus() != null ? txn.getTxnStatus().toUpperCase() : "INITIATED";
        String stage = txn.getStage() != null ? txn.getStage().toUpperCase() : "INITIATED";

        LocalDateTime initiatedTime = txn.getInitiatedAt() != null ? txn.getInitiatedAt() : txn.getCreatedAt();
        LocalDateTime resolvedTime = txn.getCompletedAt() != null ? txn.getCompletedAt() : txn.getUpdatedAt();
        if (resolvedTime == null) {
            resolvedTime = initiatedTime;
        }

        // 1. INITIATED Stage
        stages.add(StageTimelineDto.builder()
                .stage("INITIATED")
                .status("COMPLETED")
                .timestamp(initiatedTime)
                .description("Transaction created and request validated")
                .build());

        // 2. AUTHORIZED / RISK_EVALUATION Stage
        if ("CANCELLED".equals(status)) {
            stages.add(StageTimelineDto.builder()
                    .stage("AUTHORIZED")
                    .status("COMPLETED")
                    .timestamp(initiatedTime)
                    .description("Transaction authorization passed prior to cancellation")
                    .build());

            stages.add(StageTimelineDto.builder()
                    .stage("CANCELLED")
                    .status("CANCELLED")
                    .timestamp(resolvedTime)
                    .description(txn.getCancelReason() != null ? txn.getCancelReason() : "Customer cancelled transaction within grace window")
                    .build());
            return stages;
        }

        if ("RESERVED".equals(status) || "RISK_HELD".equals(stage)) {
            stages.add(StageTimelineDto.builder()
                    .stage("RISK_HELD")
                    .status("HELD")
                    .timestamp(resolvedTime)
                    .description("Transaction flagged by risk engine and held for administrative AML review")
                    .build());
            return stages;
        }

        if ("FAILED".equals(status) || "ROLLED_BACK".equals(status)) {
            stages.add(StageTimelineDto.builder()
                    .stage("FAILED")
                    .status("FAILED")
                    .timestamp(resolvedTime)
                    .description("Transaction settlement failed or rolled back")
                    .build());
            return stages;
        }

        if ("INITIATED".equals(status) || "PENDING".equals(status)) {
            stages.add(StageTimelineDto.builder()
                    .stage("AUTHORIZED")
                    .status("CURRENT")
                    .timestamp(null)
                    .description("Awaiting risk screening and balance authorization")
                    .build());
            stages.add(StageTimelineDto.builder()
                    .stage("PROCESSING")
                    .status("PENDING")
                    .timestamp(null)
                    .description("Core ledger settlement pending")
                    .build());
            stages.add(StageTimelineDto.builder()
                    .stage("SETTLED")
                    .status("PENDING")
                    .timestamp(null)
                    .description("Posting confirmation pending")
                    .build());
            return stages;
        }

        if ("AUTHORIZED".equals(status)) {
            stages.add(StageTimelineDto.builder()
                    .stage("AUTHORIZED")
                    .status("COMPLETED")
                    .timestamp(initiatedTime)
                    .description("Risk screening and balance check approved")
                    .build());
            stages.add(StageTimelineDto.builder()
                    .stage("PROCESSING")
                    .status("CURRENT")
                    .timestamp(null)
                    .description("Queued for double-entry settlement execution")
                    .build());
            stages.add(StageTimelineDto.builder()
                    .stage("SETTLED")
                    .status("PENDING")
                    .timestamp(null)
                    .description("Final posting confirmation pending")
                    .build());
            return stages;
        }

        if ("PROCESSING".equals(status)) {
            stages.add(StageTimelineDto.builder()
                    .stage("AUTHORIZED")
                    .status("COMPLETED")
                    .timestamp(initiatedTime)
                    .description("Risk screening and balance check approved")
                    .build());
            stages.add(StageTimelineDto.builder()
                    .stage("PROCESSING")
                    .status("CURRENT")
                    .timestamp(txn.getUpdatedAt() != null ? txn.getUpdatedAt() : initiatedTime)
                    .description("Claimed for settlement and executing CBS ledger mutations")
                    .build());
            stages.add(StageTimelineDto.builder()
                    .stage("SETTLED")
                    .status("PENDING")
                    .timestamp(null)
                    .description("Awaiting final posting reference confirmation")
                    .build());
            return stages;
        }

        // Settled / Committed / Posted
        stages.add(StageTimelineDto.builder()
                .stage("AUTHORIZED")
                .status("COMPLETED")
                .timestamp(initiatedTime)
                .description("Risk screening and balance check approved")
                .build());
        stages.add(StageTimelineDto.builder()
                .stage("PROCESSING")
                .status("COMPLETED")
                .timestamp(resolvedTime)
                .description("Atomic balance mutation executed in core ledger")
                .build());
        stages.add(StageTimelineDto.builder()
                .stage("SETTLED")
                .status("COMPLETED")
                .timestamp(resolvedTime)
                .description("Transaction posted and double-entry mutation committed")
                .build());

        if ("REVERSED".equals(status) || txn.getReversalOfTxnId() != null) {
            stages.add(StageTimelineDto.builder()
                    .stage("REVERSED")
                    .status("REVERSED")
                    .timestamp(resolvedTime)
                    .description("Transaction reversed by administrator via linked compensating record")
                    .build());
        }

        return stages;
    }
}

