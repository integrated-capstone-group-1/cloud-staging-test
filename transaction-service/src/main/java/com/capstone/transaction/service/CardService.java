package com.capstone.transaction.service;

import com.capstone.common.dto.CardEcomRequest;
import com.capstone.common.dto.CardResponse;
import com.capstone.common.dto.CardStatusRequest;
import com.capstone.common.exception.CardNotFoundException;
import com.capstone.common.exception.InvalidCardOperationException;
import com.capstone.transaction.entity.oracle.CardMaster;
import com.capstone.transaction.repository.oracle.CardMasterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Service for virtual card operations.
 * Manages card lifecycle: list, freeze/unfreeze, e-commerce toggle.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardService {

    private final CardMasterRepository cardMasterRepository;

    /**
     * Get all cards for a customer
     */
    @Transactional(readOnly = true)
    public List<CardResponse> getCards(String customerId) {
        List<CardMaster> cards = cardMasterRepository.findByCustomerId(customerId);
        
        return cards.stream()
                .map(this::toCardResponse)
                .collect(Collectors.toList());
    }

    /**
     * Get a specific card by ID for a customer
     */
    @Transactional(readOnly = true)
    public CardResponse getCard(String cardId, String customerId) {
        CardMaster card = cardMasterRepository.findByCardIdAndCustomerId(cardId, customerId)
                .orElseThrow(() -> new CardNotFoundException("Card not found: " + cardId));
        
        return toCardResponse(card);
    }

    /**
     * Update card status (freeze/unfreeze)
     * Only allows ACTIVE <-> FROZEN transitions
     */
    @Transactional
    public CardResponse updateCardStatus(String cardId, String customerId, CardStatusRequest request) {
        CardMaster card = cardMasterRepository.findByCardIdAndCustomerId(cardId, customerId)
                .orElseThrow(() -> new CardNotFoundException("Card not found: " + cardId));

        String newStatus = request.getStatus();
        
        // Validate status transition
        if (!isValidStatusTransition(card.getCardStatus(), newStatus)) {
            throw new InvalidCardOperationException(
                    String.format("Invalid status transition from %s to %s", card.getCardStatus(), newStatus));
        }

        card.setCardStatus(newStatus);
        card.setUpdatedAt(LocalDateTime.now());
        card.setUpdatedBy(customerId);

        CardMaster saved = cardMasterRepository.save(card);
        log.info("Card {} status updated to {} for customer {}", cardId, newStatus, customerId);

        return toCardResponse(saved);
    }

    /**
     * Enable or disable e-commerce transactions
     */
    @Transactional
    public CardResponse updateEcomStatus(String cardId, String customerId, CardEcomRequest request) {
        CardMaster card = cardMasterRepository.findByCardIdAndCustomerId(cardId, customerId)
                .orElseThrow(() -> new CardNotFoundException("Card not found: " + cardId));

        // Blocked cards cannot have e-commerce toggled
        if ("BLOCKED".equals(card.getCardStatus())) {
            throw new InvalidCardOperationException("Cannot modify e-commerce on a blocked card");
        }

        card.setEcomEnabled(request.getEcomEnabled());
        card.setUpdatedAt(LocalDateTime.now());
        card.setUpdatedBy(customerId);

        CardMaster saved = cardMasterRepository.save(card);
        log.info("Card {} e-commerce set to {} for customer {}", cardId, request.getEcomEnabled(), customerId);

        return toCardResponse(saved);
    }

    /**
     * Validate if a card can be used for a transaction
     * Throws exception if card is frozen, blocked, or e-commerce is disabled
     */
    @Transactional(readOnly = true)
    public void validateCardForTransaction(String cardId, boolean isEcommerceTransaction) {
        CardMaster card = cardMasterRepository.findById(cardId)
                .orElseThrow(() -> new CardNotFoundException("Card not found: " + cardId));

        // Check card status
        if ("FROZEN".equals(card.getCardStatus())) {
            throw new InvalidCardOperationException("Card is frozen");
        }
        
        if ("BLOCKED".equals(card.getCardStatus())) {
            throw new InvalidCardOperationException("Card is blocked");
        }

        // Check e-commerce flag for e-commerce transactions
        if (isEcommerceTransaction && !card.isEcomEnabled()) {
            throw new InvalidCardOperationException("ECOM_TRANSACTIONS_DISABLED");
        }
    }

    /**
     * Find card by card token (used during payment processing)
     */
    @Transactional(readOnly = true)
    public CardMaster findByCardToken(String cardToken) {
        return cardMasterRepository.findByCardToken(cardToken)
                .orElseThrow(() -> new CardNotFoundException("Card not found for token"));
    }

    private boolean isValidStatusTransition(String currentStatus, String newStatus) {
        // ACTIVE -> FROZEN is allowed
        if ("ACTIVE".equals(currentStatus) && "FROZEN".equals(newStatus)) {
            return true;
        }
        // FROZEN -> ACTIVE is allowed
        if ("FROZEN".equals(currentStatus) && "ACTIVE".equals(newStatus)) {
            return true;
        }
        // BLOCKED cannot be changed (admin only)
        return false;
    }

    private CardResponse toCardResponse(CardMaster card) {
        return CardResponse.builder()
                .cardId(card.getCardId())
                .maskedPan(card.getMaskedPan())
                .expiryDate(card.getExpiryDate())
                .cardStatus(card.getCardStatus())
                .ecomEnabled(card.getEcomEnabled())
                .dailyLimit(card.getDailyLimit())
                .build();
    }
}