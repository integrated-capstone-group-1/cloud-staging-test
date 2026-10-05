package com.capstone.forex.controller;

import com.capstone.forex.dto.FxConvertRequest;
import com.capstone.forex.dto.FxConvertResponse;
import com.capstone.forex.dto.FxQuoteResponse;
import com.capstone.forex.exception.FxQuoteExpiredException;
import com.capstone.forex.exception.FxRateStaleException;
import com.capstone.forex.service.ForexQuoteService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Controller exposing FX Quote generation and own-pocket conversion endpoints (CONTRACTS.md § 2.2).
 */
@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
public class ForexQuoteController {

    private final ForexQuoteService forexQuoteService;

    @GetMapping("/quote")
    public ResponseEntity<FxQuoteResponse> getQuote(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam("amount") BigDecimal amount) {

        FxQuoteResponse quote = forexQuoteService.generateQuote(from, to, amount);
        return ResponseEntity.ok(quote);
    }

    @PostMapping("/convert")
    public ResponseEntity<FxConvertResponse> convert(
            @Valid @RequestBody FxConvertRequest request) {

        FxConvertResponse response = forexQuoteService.convert(request);
        return ResponseEntity.ok(response);
    }

    @ExceptionHandler(FxRateStaleException.class)
    public ResponseEntity<Map<String, String>> handleRateStale(FxRateStaleException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error_code", "FX_RATE_STALE",
                "message", ex.getMessage()
        ));
    }

    @ExceptionHandler(FxQuoteExpiredException.class)
    public ResponseEntity<Map<String, String>> handleQuoteExpired(FxQuoteExpiredException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error_code", "QUOTE_EXPIRED",
                "message", ex.getMessage()
        ));
    }
}
