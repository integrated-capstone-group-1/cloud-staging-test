package com.capstone.transaction.service;

import com.capstone.common.dto.CardEcomRequest;
import com.capstone.common.dto.CardResponse;
import com.capstone.common.dto.CardStatusRequest;
import com.capstone.common.exception.InvalidCardOperationException;
import com.capstone.transaction.entity.oracle.CardMaster;
import com.capstone.transaction.repository.oracle.CardMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VirtualCardControlsTest {

    @Mock
    private CardMasterRepository cardMasterRepository;

    @InjectMocks
    private CardService cardService;

    private CardMaster activeCard;
    private CardMaster frozenCard;
    private CardMaster blockedCard;

    private static final String CUSTOMER_ID = "cust-juan-01";
    private static final String CARD_ID = "card-juan-01";

    @BeforeEach
    void setUp() {
        activeCard = CardMaster.builder()
                .cardId(CARD_ID)
                .customerId(CUSTOMER_ID)
                .accountId("acct-juan-php-01")
                .cardToken("tok_4532888821")
                .maskedPan("4532-XXXX-XXXX-8821")
                .expiryDate("12/28")
                .cardStatus("ACTIVE")
                .ecomEnabled(1)
                .dailyLimit(new BigDecimal("50000.00"))
                .createdAt(LocalDateTime.now())
                .createdBy(CUSTOMER_ID)
                .build();

        frozenCard = CardMaster.builder()
                .cardId("card-frozen-01")
                .customerId(CUSTOMER_ID)
                .accountId("acct-juan-php-01")
                .cardToken("tok_4532999921")
                .maskedPan("4532-XXXX-XXXX-9921")
                .expiryDate("12/28")
                .cardStatus("FROZEN")
                .ecomEnabled(1)
                .dailyLimit(new BigDecimal("50000.00"))
                .createdAt(LocalDateTime.now())
                .createdBy(CUSTOMER_ID)
                .build();

        blockedCard = CardMaster.builder()
                .cardId("card-blocked-01")
                .customerId(CUSTOMER_ID)
                .accountId("acct-juan-php-01")
                .cardToken("tok_4532111121")
                .maskedPan("4532-XXXX-XXXX-1121")
                .expiryDate("12/28")
                .cardStatus("BLOCKED")
                .ecomEnabled(1)
                .dailyLimit(new BigDecimal("50000.00"))
                .createdAt(LocalDateTime.now())
                .createdBy(CUSTOMER_ID)
                .build();
    }

    @Nested
    @DisplayName("PUT /api/v1/cards/{id}/status - Card Freeze/Unfreeze")
    class UpdateCardStatus {

        @Test
        @DisplayName("Should freeze an active card")
        void shouldFreezeActiveCard() {
            CardStatusRequest request = new CardStatusRequest("FROZEN");

            when(cardMasterRepository.findByCardIdAndCustomerId(CARD_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(activeCard));
            when(cardMasterRepository.save(any(CardMaster.class))).thenAnswer(inv -> inv.getArgument(0));

            CardResponse result = cardService.updateCardStatus(CARD_ID, CUSTOMER_ID, request);

            assertThat(result.getCardStatus()).isEqualTo("FROZEN");
            verify(cardMasterRepository).save(any(CardMaster.class));
        }

        @Test
        @DisplayName("Should unfreeze a frozen card")
        void shouldUnfreezeFrozenCard() {
            CardStatusRequest request = new CardStatusRequest("ACTIVE");

            when(cardMasterRepository.findByCardIdAndCustomerId("card-frozen-01", CUSTOMER_ID))
                    .thenReturn(Optional.of(frozenCard));
            when(cardMasterRepository.save(any(CardMaster.class))).thenAnswer(inv -> inv.getArgument(0));

            CardResponse result = cardService.updateCardStatus("card-frozen-01", CUSTOMER_ID, request);

            assertThat(result.getCardStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("Should reject invalid status transition from ACTIVE to BLOCKED")
        void shouldRejectInvalidTransitionToBlocked() {
            CardStatusRequest request = new CardStatusRequest("BLOCKED");

            when(cardMasterRepository.findByCardIdAndCustomerId(CARD_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(activeCard));

            assertThatThrownBy(() -> cardService.updateCardStatus(CARD_ID, CUSTOMER_ID, request))
                    .isInstanceOf(InvalidCardOperationException.class)
                    .hasMessageContaining("Invalid status transition");
        }
    }

    @Nested
    @DisplayName("PUT /api/v1/cards/{id}/ecom - E-Commerce Toggle")
    class UpdateEcomStatus {

        @Test
        @DisplayName("Should enable e-commerce on card")
        void shouldEnableEcommerce() {
            CardEcomRequest request = new CardEcomRequest(1);

            when(cardMasterRepository.findByCardIdAndCustomerId(CARD_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(activeCard));
            when(cardMasterRepository.save(any(CardMaster.class))).thenAnswer(inv -> inv.getArgument(0));

            CardResponse result = cardService.updateEcomStatus(CARD_ID, CUSTOMER_ID, request);

            assertThat(result.getEcomEnabled()).isEqualTo(1);
        }

        @Test
        @DisplayName("Should disable e-commerce on card")
        void shouldDisableEcommerce() {
            CardEcomRequest request = new CardEcomRequest(0);

            when(cardMasterRepository.findByCardIdAndCustomerId(CARD_ID, CUSTOMER_ID))
                    .thenReturn(Optional.of(activeCard));
            when(cardMasterRepository.save(any(CardMaster.class))).thenAnswer(inv -> inv.getArgument(0));

            CardResponse result = cardService.updateEcomStatus(CARD_ID, CUSTOMER_ID, request);

            assertThat(result.getEcomEnabled()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Card Freeze Stops Subsequent Spend Authorization")
    class CardFreezeStopsSpend {

        @Test
        @DisplayName("Should reject transaction on frozen card")
        void shouldRejectTransactionOnFrozenCard() {
            when(cardMasterRepository.findById("card-frozen-01"))
                    .thenReturn(Optional.of(frozenCard));

            assertThatThrownBy(() -> cardService.validateCardForTransaction("card-frozen-01", false))
                    .isInstanceOf(InvalidCardOperationException.class)
                    .hasMessageContaining("frozen");
        }

        @Test
        @DisplayName("Should allow transaction on active card")
        void shouldAllowTransactionOnActiveCard() {
            when(cardMasterRepository.findById(CARD_ID))
                    .thenReturn(Optional.of(activeCard));

            cardService.validateCardForTransaction(CARD_ID, false);
        }
    }

    @Nested
    @DisplayName("Toggling E-Commerce Off Rejects E-Commerce Payments")
    class EcommerceToggleRejectsPayments {

        @Test
        @DisplayName("Should reject e-commerce transaction when ecom_enabled is 0")
        void shouldRejectEcommerceWhenDisabled() {
            CardMaster cardWithEcomDisabled = CardMaster.builder()
                    .cardId(CARD_ID)
                    .customerId(CUSTOMER_ID)
                    .cardStatus("ACTIVE")
                    .ecomEnabled(0)
                    .build();

            when(cardMasterRepository.findById(CARD_ID))
                    .thenReturn(Optional.of(cardWithEcomDisabled));

            assertThatThrownBy(() -> cardService.validateCardForTransaction(CARD_ID, true))
                    .isInstanceOf(InvalidCardOperationException.class)
                    .hasMessageContaining("ECOM_TRANSACTIONS_DISABLED");
        }

        @Test
        @DisplayName("Should allow e-commerce transaction when ecom_enabled is 1")
        void shouldAllowEcommerceWhenEnabled() {
            when(cardMasterRepository.findById(CARD_ID))
                    .thenReturn(Optional.of(activeCard));

            cardService.validateCardForTransaction(CARD_ID, true);
        }
    }

}
