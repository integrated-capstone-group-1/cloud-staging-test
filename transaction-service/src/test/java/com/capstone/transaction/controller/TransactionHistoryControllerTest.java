package com.capstone.transaction.controller;

import com.capstone.transaction.dto.PageResponse;
import com.capstone.transaction.dto.StageTimelineDto;
import com.capstone.transaction.dto.TransactionDetailsResponse;
import com.capstone.transaction.service.TransactionHistoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionHistoryControllerTest {

    @Mock
    private TransactionHistoryService transactionHistoryService;

    @InjectMocks
    private TransactionHistoryController transactionHistoryController;

    @Test
    @DisplayName("GET /v1/transactions/{id} returns details and status 200")
    void getTransactionById_success() {
        TransactionDetailsResponse details = TransactionDetailsResponse.builder()
                .transactionId("txn-001")
                .status("COMMITTED")
                .stage("COMPLETED")
                .amount(new BigDecimal("250.0000"))
                .currency("PHP")
                .auditRef("/api/v1/ledger/audit/txn-001")
                .stages(List.of(
                        StageTimelineDto.builder().stage("INITIATED").status("COMPLETED").build(),
                        StageTimelineDto.builder().stage("AUTHORIZED").status("COMPLETED").build()
                ))
                .build();

        when(transactionHistoryService.getTransactionById("txn-001")).thenReturn(details);

        ResponseEntity<TransactionDetailsResponse> response = transactionHistoryController.getTransactionById("txn-001");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTransactionId()).isEqualTo("txn-001");
        assertThat(response.getBody().getStages()).hasSize(2);
        assertThat(response.getBody().getAuditRef()).isEqualTo("/api/v1/ledger/audit/txn-001");
        verify(transactionHistoryService).getTransactionById("txn-001");
    }

    @Test
    @DisplayName("GET /v1/transactions returns paginated history")
    void getTransactions_success() {
        TransactionDetailsResponse item = TransactionDetailsResponse.builder()
                .transactionId("txn-002")
                .status("COMMITTED")
                .auditRef("/api/v1/ledger/audit/txn-002")
                .build();

        PageResponse<TransactionDetailsResponse> page = PageResponse.<TransactionDetailsResponse>builder()
                .content(List.of(item))
                .page(0)
                .size(10)
                .totalElements(1)
                .totalPages(1)
                .build();

        when(transactionHistoryService.getTransactions(any(), any(), any(), any(), any(), eq(0), eq(10)))
                .thenReturn(page);

        ResponseEntity<PageResponse<TransactionDetailsResponse>> response = transactionHistoryController.getTransactions(
                "COMMITTED", null, null, "cust-1", null, 0, 10);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);
        assertThat(response.getBody().getContent().get(0).getAuditRef()).isEqualTo("/api/v1/ledger/audit/txn-002");
    }
}
