package com.capstone.transaction.controller;

import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.CardEcomRequest;
import com.capstone.common.dto.CardResponse;
import com.capstone.common.dto.CardStatusRequest;
import com.capstone.common.exception.InvalidTokenException;
import com.capstone.common.security.SecurityUtils;
import com.capstone.transaction.service.CardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for virtual card management.
 * 
 * Endpoints:
 * - GET /api/v1/cards - List customer's cards
 * - GET /api/v1/cards/{id} - Get specific card details
 * - PUT /api/v1/cards/{id}/status - Freeze/unfreeze card
 * - PUT /api/v1/cards/{id}/ecom - Enable/disable e-commerce
 */
@RestController
@RequestMapping("/api/v1/cards")
@RequiredArgsConstructor
public class CardController {

    private final CardService cardService;

    /**
     * Get all virtual cards for the authenticated customer
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CardResponse>>> getCards() {
        String customerId = SecurityUtils.getCurrentCustomerId()
                .orElseThrow(() -> new InvalidTokenException("Authenticated customer ID not found"));
        List<CardResponse> cards = cardService.getCards(customerId);
        return ResponseEntity.ok(ApiResponse.ok("Cards retrieved successfully", cards));
    }

    /**
     * Get all virtual cards for a specific customer (staff / admin access)
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<List<CardResponse>>> getCardsByCustomer(@PathVariable String customerId) {
        List<CardResponse> cards = cardService.getCards(customerId);
        return ResponseEntity.ok(ApiResponse.ok("Cards retrieved successfully", cards));
    }

    /**
     * Get a specific card by ID
     */
    @GetMapping("/{cardId}")
    public ResponseEntity<ApiResponse<CardResponse>> getCard(@PathVariable String cardId) {
        String customerId = SecurityUtils.getCurrentCustomerId()
                .orElseThrow(() -> new InvalidTokenException("Authenticated customer ID not found"));
        CardResponse card = cardService.getCard(cardId, customerId);
        return ResponseEntity.ok(ApiResponse.ok("Card retrieved successfully", card));
    }

    /**
     * Update card status (freeze/unfreeze)
     * PUT /api/v1/cards/{id}/status with body {"status": "FROZEN"} or {"status": "ACTIVE"}
     */
    @PutMapping("/{cardId}/status")
    public ResponseEntity<ApiResponse<CardResponse>> updateCardStatus(
            @PathVariable String cardId,
            @Valid @RequestBody CardStatusRequest request) {
        String customerId = SecurityUtils.getCurrentCustomerId()
                .orElseThrow(() -> new InvalidTokenException("Authenticated customer ID not found"));
        CardResponse card = cardService.updateCardStatus(cardId, customerId, request);
        return ResponseEntity.ok(ApiResponse.ok("Card status updated successfully", card));
    }

    /**
     * Enable or disable e-commerce transactions
     * PUT /api/v1/cards/{id}/ecom with body {"ecom_enabled": 1} or {"ecom_enabled": 0}
     */
    @PutMapping("/{cardId}/ecom")
    public ResponseEntity<ApiResponse<CardResponse>> updateEcomStatus(
            @PathVariable String cardId,
            @Valid @RequestBody CardEcomRequest request) {
        String customerId = SecurityUtils.getCurrentCustomerId()
                .orElseThrow(() -> new InvalidTokenException("Authenticated customer ID not found"));
        CardResponse card = cardService.updateEcomStatus(cardId, customerId, request);
        return ResponseEntity.ok(ApiResponse.ok("E-commerce status updated successfully", card));
    }
}