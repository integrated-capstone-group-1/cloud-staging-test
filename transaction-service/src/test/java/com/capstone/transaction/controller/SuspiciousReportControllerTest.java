package com.capstone.transaction.controller;

import com.capstone.transaction.dto.SuspiciousReportRequest;
import com.capstone.transaction.dto.SuspiciousReportResponse;
import com.capstone.transaction.service.SuspiciousReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SuspiciousReportControllerTest {

    @Mock
    private SuspiciousReportService suspiciousReportService;

    @InjectMocks
    private SuspiciousReportController suspiciousReportController;

    @Test
    @DisplayName("POST /v1/reports/suspicious creates case and returns 201 Created")
    void reportSuspicious_success() {
        SuspiciousReportRequest request = SuspiciousReportRequest.builder()
                .transactionId("txn-99")
                .reason("UNRECOGNIZED_TRANSACTION")
                .notes("Unknown merchant")
                .build();

        SuspiciousReportResponse expected = SuspiciousReportResponse.builder()
                .caseId("CASE-99")
                .transactionId("txn-99")
                .status("OPEN")
                .customerNotified(true)
                .createdAt(LocalDateTime.now())
                .build();

        when(suspiciousReportService.reportSuspiciousTransaction(any(SuspiciousReportRequest.class)))
                .thenReturn(expected);

        ResponseEntity<SuspiciousReportResponse> response = suspiciousReportController.reportSuspicious(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCaseId()).isEqualTo("CASE-99");
        assertThat(response.getBody().isCustomerNotified()).isTrue();
        verify(suspiciousReportService).reportSuspiciousTransaction(request);
    }

    @Test
    @DisplayName("POST /api/v1/transfers/{id}/report-suspicious convenience endpoint")
    void reportSuspiciousByPath_success() {
        SuspiciousReportResponse expected = SuspiciousReportResponse.builder()
                .caseId("CASE-100")
                .transactionId("txn-100")
                .status("OPEN")
                .customerNotified(true)
                .build();

        when(suspiciousReportService.reportSuspiciousTransaction(any(SuspiciousReportRequest.class)))
                .thenReturn(expected);

        ResponseEntity<SuspiciousReportResponse> response = suspiciousReportController.reportSuspiciousByPath("txn-100", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCaseId()).isEqualTo("CASE-100");
    }

    @Test
    @DisplayName("GET /v1/reports/suspicious returns admin cases queue")
    void getAdminCaseQueue_success() {
        SuspiciousReportResponse item = SuspiciousReportResponse.builder()
                .caseId("CASE-101")
                .status("OPEN")
                .build();

        when(suspiciousReportService.getAdminCaseQueue("OPEN")).thenReturn(List.of(item));

        ResponseEntity<List<SuspiciousReportResponse>> response = suspiciousReportController.getAdminCaseQueue("OPEN");

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
    }
}
