package com.capstone.transaction.controller;

import com.capstone.transaction.service.StatementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StatementControllerTest {

    @Mock
    private StatementService statementService;

    @InjectMocks
    private StatementController statementController;

    @Test
    @DisplayName("GET /v1/statements returns PDF with Content-Type application/pdf and attachment header")
    void downloadStatement_success() {
        byte[] fakePdf = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        when(statementService.generateAccountStatement(any(), any(), any(), any())).thenReturn(fakePdf);

        ResponseEntity<byte[]> response = statementController.downloadStatement(
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 10, 7),
                "acct-01",
                "cust-01"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment; filename=\"statement-");
        assertThat(response.getBody()).isEqualTo(fakePdf);
        verify(statementService).generateAccountStatement(any(), any(), eq("acct-01"), eq("cust-01"));
    }

    @Test
    @DisplayName("GET /v1/statements/monthly returns monthly e-statement PDF")
    void downloadMonthlyEStatement_success() {
        byte[] fakePdf = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        when(statementService.generateMonthlyEStatement(eq(2026), eq(10), any(), any())).thenReturn(fakePdf);

        ResponseEntity<byte[]> response = statementController.downloadMonthlyEStatement(
                2026, 10, "acct-01", "cust-01"
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("e-statement-2026-10.pdf");
        assertThat(response.getBody()).isEqualTo(fakePdf);
    }

    @Test
    @DisplayName("GET /v1/statements/eod returns EOD batch reconciliation PDF")
    void downloadEodReport_success() {
        byte[] fakePdf = "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.ISO_8859_1);
        when(statementService.generateEodReport(any())).thenReturn(fakePdf);

        ResponseEntity<byte[]> response = statementController.downloadEodReport(LocalDate.now());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains("eod-reconciliation-");
        assertThat(response.getBody()).isEqualTo(fakePdf);
    }
}

