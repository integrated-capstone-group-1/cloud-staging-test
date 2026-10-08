package com.capstone.orchestrator.controller;

import com.capstone.common.dto.AccountDTO;
import com.capstone.orchestrator.client.AccountsClient;
import com.capstone.orchestrator.service.AccountOwnershipService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PocketsControllerTest {

    @Mock
    private AccountOwnershipService ownershipService;

    @Mock
    private AccountsClient accountsClient;

    @InjectMocks
    private PocketsController pocketsController;

    @Test
    @DisplayName("SEAM-ROH-07: Verifies account ownership and retrieves customer multi-currency pockets")
    void shouldVerifyOwnershipAndReturnPockets() {
        String accountId = "acct-php-01";
        String customerId = "cust-user-101";

        AccountDTO primaryAccount = new AccountDTO(accountId, customerId, "SAVINGS", "ACTIVE", new BigDecimal("5000.00"), "PHP", java.time.LocalDateTime.now());
        AccountDTO eurPocket = new AccountDTO("acct-eur-01", customerId, "SAVINGS", "ACTIVE", new BigDecimal("100.00"), "EUR", java.time.LocalDateTime.now());

        when(accountsClient.getAccount(accountId)).thenReturn(primaryAccount);
        when(accountsClient.getCustomerPockets(customerId)).thenReturn(List.of(primaryAccount, eurPocket));

        ResponseEntity<List<AccountDTO>> response = pocketsController.getAccountPockets(accountId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).hasSize(2);

        verify(ownershipService).verifyAccountOwnership(accountId, "view pockets");
        verify(accountsClient).getAccount(accountId);
        verify(accountsClient).getCustomerPockets(customerId);
    }

    @Test
    @DisplayName("Aborts pocket query when ownership verification fails")
    void shouldAbortWhenOwnershipFails() {
        String foreignAccountId = "acct-foreign-01";

        doThrow(new AccessDeniedException("Access denied: You do not own account " + foreignAccountId))
                .when(ownershipService).verifyAccountOwnership(foreignAccountId, "view pockets");

        assertThatThrownBy(() -> pocketsController.getAccountPockets(foreignAccountId))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied");

        verifyNoInteractions(accountsClient);
    }
}