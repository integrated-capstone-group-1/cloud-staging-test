package com.capstone.orchestrator.service;

import com.capstone.common.dto.AccountDTO;
import com.capstone.orchestrator.client.AccountsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountOwnershipServiceTest {

    @Mock
    private AccountsClient accountsClient;

    @InjectMocks
    private AccountOwnershipService ownershipService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticate(String customerId, String role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                customerId,
                null,
                List.of(new SimpleGrantedAuthority(role.startsWith("ROLE_") ? role : "ROLE_" + role))
        );
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    @DisplayName("Allows request when authenticated customer owns the target account")
    void shouldAllowWhenCustomerOwnsAccount() {
        authenticate("cust-101", "ROLE_CUSTOMER");

        AccountDTO account = new AccountDTO(
                "acct-101-php-01", "cust-101", "SAVINGS", "ACTIVE",
                BigDecimal.valueOf(1000), "PHP", LocalDateTime.now()
        );
        when(accountsClient.getAccount("acct-101-php-01")).thenReturn(account);

        assertThatCode(() -> ownershipService.verifyAccountOwnership("acct-101-php-01", "transfer"))
                .doesNotThrowAnyException();

        verify(accountsClient).getAccount("acct-101-php-01");
    }

    @Test
    @DisplayName("Rejects with 403 AccessDeniedException when customer attempts to access another user's account")
    void shouldRejectWhenCustomerDoesNotOwnAccount() {
        authenticate("cust-101", "ROLE_CUSTOMER");

        AccountDTO targetAccount = new AccountDTO(
                "acct-999-php-01", "cust-999", "SAVINGS", "ACTIVE",
                BigDecimal.valueOf(5000), "PHP", LocalDateTime.now()
        );
        when(accountsClient.getAccount("acct-999-php-01")).thenReturn(targetAccount);

        assertThatThrownBy(() -> ownershipService.verifyAccountOwnership("acct-999-php-01", "debit payment"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("Access denied: You do not own account");
    }

    @Test
    @DisplayName("Allows request when caller holds ROLE_ADMIN or ROLE_INTERNAL")
    void shouldAllowPrivilegedCallerBypass() {
        authenticate("admin-user", "ROLE_ADMIN");

        assertThatCode(() -> ownershipService.verifyAccountOwnership("acct-any-01", "admin inspect"))
                .doesNotThrowAnyException();

        verifyNoInteractions(accountsClient);
    }
}

