package com.capstone.transaction.controller;

import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.CardEcomRequest;
import com.capstone.common.dto.CardResponse;
import com.capstone.common.dto.CardStatusRequest;
import com.capstone.transaction.service.CardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardControllerTest {

    @Mock
    private CardService cardService;

    @InjectMocks
    private CardController cardController;

    private static final String CUSTOMER_ID = "cust-juan-01";
    private static final String CARD_ID = "card-juan-01";

    @BeforeEach
    void setUp() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(CUSTOMER_ID, null, Collections.emptyList());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @Test
    @DisplayName("GET /api/v1/cards returns customer cards")
    void getCards_success() {
        CardResponse card = CardResponse.builder()
                .cardId(CARD_ID)
                .maskedPan("4532-XXXX-XXXX-8821")
                .expiryDate("12/28")
                .cardStatus("ACTIVE")
                .ecomEnabled(1)
                .dailyLimit(new BigDecimal("50000.00"))
                .build();

        when(cardService.getCards(CUSTOMER_ID)).thenReturn(List.of(card));

        ResponseEntity<ApiResponse<List<CardResponse>>> response = cardController.getCards();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data()).hasSize(1);
        assertThat(response.getBody().data().get(0).getCardId()).isEqualTo(CARD_ID);
        verify(cardService).getCards(CUSTOMER_ID);
    }

    @Test
    @DisplayName("PUT /api/v1/cards/{id}/status updates card status")
    void updateCardStatus_success() {
        CardStatusRequest request = new CardStatusRequest("FROZEN");
        CardResponse card = CardResponse.builder()
                .cardId(CARD_ID)
                .cardStatus("FROZEN")
                .build();

        when(cardService.updateCardStatus(eq(CARD_ID), eq(CUSTOMER_ID), any(CardStatusRequest.class)))
                .thenReturn(card);

        ResponseEntity<ApiResponse<CardResponse>> response = cardController.updateCardStatus(CARD_ID, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().getCardStatus()).isEqualTo("FROZEN");
        verify(cardService).updateCardStatus(eq(CARD_ID), eq(CUSTOMER_ID), any(CardStatusRequest.class));
    }

    @Test
    @DisplayName("PUT /api/v1/cards/{id}/ecom updates ecom toggle")
    void updateEcomStatus_success() {
        CardEcomRequest request = new CardEcomRequest(0);
        CardResponse card = CardResponse.builder()
                .cardId(CARD_ID)
                .ecomEnabled(0)
                .build();

        when(cardService.updateEcomStatus(eq(CARD_ID), eq(CUSTOMER_ID), any(CardEcomRequest.class)))
                .thenReturn(card);

        ResponseEntity<ApiResponse<CardResponse>> response = cardController.updateEcomStatus(CARD_ID, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().getEcomEnabled()).isEqualTo(0);
        verify(cardService).updateEcomStatus(eq(CARD_ID), eq(CUSTOMER_ID), any(CardEcomRequest.class));
    }
}