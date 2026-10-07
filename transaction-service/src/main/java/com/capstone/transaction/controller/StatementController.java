package com.capstone.transaction.controller;

import com.capstone.transaction.service.StatementService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Controller exposing statement PDF downloads and reusable report generators (DIG-92 / T-001).
 *
 * Acceptance Criteria:
 * 1) GET /v1/statements?from=&to= returns PDF
 * 2) Generator reusable for monthly e-statement and EOD reports
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class StatementController {

    private final StatementService statementService;

    /**
     * AC #1: GET /v1/statements?from=&to= returns binary PDF.
     */
    @GetMapping({"/v1/statements", "/api/v1/statements", "/api/v1/statements/download"})
    public ResponseEntity<byte[]> downloadStatement(
            @RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "accountId", required = false) String accountId,
            @RequestParam(value = "customerId", required = false) String customerId) {

        log.info("Request statement PDF download: from={}, to={}, accountId={}, customerId={}", from, to, accountId, customerId);

        byte[] pdfBytes = statementService.generateAccountStatement(from, to, accountId, customerId);

        String filename = String.format("statement-%s-%s.pdf",
                from != null ? from.toString() : "period",
                to != null ? to.toString() : "current");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * AC #2 Reusability: Monthly e-statement PDF generator.
     */
    @GetMapping({"/v1/statements/monthly", "/api/v1/statements/monthly"})
    public ResponseEntity<byte[]> downloadMonthlyEStatement(
            @RequestParam(value = "year", defaultValue = "2026") int year,
            @RequestParam(value = "month", defaultValue = "10") int month,
            @RequestParam(value = "accountId", required = false) String accountId,
            @RequestParam(value = "customerId", required = false) String customerId) {

        log.info("Request monthly e-statement PDF: year={}, month={}, accountId={}", year, month, accountId);

        byte[] pdfBytes = statementService.generateMonthlyEStatement(year, month, accountId, customerId);

        String filename = String.format("e-statement-%04d-%02d.pdf", year, month);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * AC #2 Reusability: End-of-Day (EOD) batch reconciliation report PDF generator.
     */
    @GetMapping({"/v1/statements/eod", "/api/v1/statements/eod"})
    public ResponseEntity<byte[]> downloadEodReport(
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

        LocalDate targetDate = date != null ? date : LocalDate.now();
        log.info("Request EOD batch report PDF for date={}", targetDate);

        byte[] pdfBytes = statementService.generateEodReport(targetDate);

        String filename = String.format("eod-reconciliation-%s.pdf", targetDate);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}
