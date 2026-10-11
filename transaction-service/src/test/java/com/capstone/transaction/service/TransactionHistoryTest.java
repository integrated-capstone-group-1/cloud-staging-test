package com.capstone.transaction.service;

import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.dto.PageResponse;
import com.capstone.transaction.dto.StageTimelineDto;
import com.capstone.transaction.dto.TransactionDetailsResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @InjectMocks
    private TransactionHistoryService transactionHistoryService;

    private static final String CUSTOMER_ID = "cust-user-001";
    private static final String TXN_ID = "txn-test-1001";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                CUSTOMER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("DIG-18 AC #1: GET transaction details returns status, stage and simplified timeline")
    void shouldReturnTransactionDetailsWithSimplifiedStages() {
        TransactionMaster txn = TransactionMaster.builder()
                .txnId(TXN_ID)
                .txnType("TRANSFER")
                .txnStatus("COMMITTED")
                .stage("COMPLETED")
                .debitAccountId("acct-php-01")
                .creditAccountId("acct-php-02")
                .mutationAmount(new BigDecimal("1500.0000"))
                .currencyCode("PHP")
                .customerId(CUSTOMER_ID)
                .coreReferenceId("CORE-REF-999")
                .createdAt(LocalDateTime.now().minusMinutes(5))
                .completedAt(LocalDateTime.now())
                .build();

        when(txnMasterRepository.findById(TXN_ID)).thenReturn(Optional.of(txn));

        TransactionDetailsResponse response = transactionHistoryService.getTransactionById(TXN_ID);

        assertThat(response).isNotNull();
        assertThat(response.getTransactionId()).isEqualTo(TXN_ID);
        assertThat(response.getStatus()).isEqualTo("COMMITTED");
        assertThat(response.getAmount()).isEqualByComparingTo(new BigDecimal("1500.0000"));
        assertThat(response.getCoreReferenceId()).isEqualTo("CORE-REF-999");

        // Verify AC #3: links to audit trail
        assertThat(response.getAuditRef()).isEqualTo("/api/v1/ledger/audit/" + TXN_ID);
        assertThat(response.getAuditTrailUrl()).isEqualTo("/api/v1/ledger/audit/" + TXN_ID);

        // Verify AC #1: simplified stages
        List<StageTimelineDto> stages = response.getStages();
        assertThat(stages).isNotEmpty();
        assertThat(stages).extracting(StageTimelineDto::getStage)
                .containsSubsequence("INITIATED", "AUTHORIZED", "PROCESSING", "SETTLED");
        assertThat(stages).allMatch(s -> "COMPLETED".equals(s.getStatus()));
    }

    @Test
    @DisplayName("DIG-18 AC #1: Simplified stages correctly reflect CANCELLED transaction")
    void shouldReflectCancelledStages() {
        TransactionMaster cancelledTxn = TransactionMaster.builder()
                .txnId("txn-cancelled-1")
                .txnType("TRANSFER")
                .txnStatus("CANCELLED")
                .stage("CANCELLED")
                .mutationAmount(new BigDecimal("500.0000"))
                .currencyCode("PHP")
                .customerId(CUSTOMER_ID)
                .cancelReason("Customer requested within 5s undo window")
                .createdAt(LocalDateTime.now().minusSeconds(10))
                .updatedAt(LocalDateTime.now())
                .build();

        List<StageTimelineDto> stages = transactionHistoryService.buildSimplifiedStages(cancelledTxn);

        assertThat(stages).isNotEmpty();
        assertThat(stages).extracting(StageTimelineDto::getStage)
                .contains("INITIATED", "AUTHORIZED", "CANCELLED");
        StageTimelineDto cancelledStage = stages.stream()
                .filter(s -> "CANCELLED".equals(s.getStage()))
                .findFirst()
                .orElseThrow();
        assertThat(cancelledStage.getStatus()).isEqualTo("CANCELLED");
        assertThat(cancelledStage.getDescription()).contains("Customer requested within 5s undo window");
    }

    @Test
    @DisplayName("DIG-18 AC #1: Simplified stages correctly reflect RISK_HELD / RESERVED transaction")
    void shouldReflectRiskHeldStages() {
        TransactionMaster heldTxn = TransactionMaster.builder()
                .txnId("txn-held-1")
                .txnType("TRANSFER")
                .txnStatus("RESERVED")
                .stage("RISK_HELD")
                .mutationAmount(new BigDecimal("600000.0000"))
                .currencyCode("PHP")
                .customerId(CUSTOMER_ID)
                .createdAt(LocalDateTime.now())
                .build();

        List<StageTimelineDto> stages = transactionHistoryService.buildSimplifiedStages(heldTxn);

        assertThat(stages).isNotEmpty();
        StageTimelineDto heldStage = stages.stream()
                .filter(s -> "RISK_HELD".equals(s.getStage()))
                .findFirst()
                .orElseThrow();
        assertThat(heldStage.getStatus()).isEqualTo("HELD");
        assertThat(heldStage.getDescription()).contains("held for administrative AML review");
    }

    @Test
    @DisplayName("DIG-18 AC #2: GET /v1/transactions paginated query with filters")
    void shouldReturnFilteredPaginatedTransactions() {
        TransactionMaster txn1 = TransactionMaster.builder()
                .txnId("txn-p-1")
                .txnType("TRANSFER")
                .txnStatus("COMMITTED")
                .stage("COMPLETED")
                .mutationAmount(new BigDecimal("100.0000"))
                .currencyCode("PHP")
                .customerId(CUSTOMER_ID)
                .createdAt(LocalDateTime.now())
                .build();

        Page<TransactionMaster> page = new PageImpl<>(List.of(txn1));
        when(txnMasterRepository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(page);

        PageResponse<TransactionDetailsResponse> response = transactionHistoryService.getTransactions(
                "COMMITTED",
                LocalDateTime.now().minusDays(1),
                LocalDateTime.now(),
                CUSTOMER_ID,
                null,
                0,
                10
        );

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getContent().get(0).getTransactionId()).isEqualTo("txn-p-1");
        assertThat(response.getContent().get(0).getAuditRef()).isEqualTo("/api/v1/ledger/audit/txn-p-1");
    }

    @Test
    @DisplayName("DIG-18: Non-existent transaction throws ResourceNotFoundException")
    void shouldThrowWhenTransactionNotFound() {
        when(txnMasterRepository.findById("invalid-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionHistoryService.getTransactionById("invalid-id"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction invalid-id not found");
    }
}

