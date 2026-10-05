package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.CustomerRepository;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.common.dto.AccountDTO;
import com.capstone.common.dto.AccountLockResponse;
import com.capstone.common.dto.LockAccountRequest;
import com.capstone.common.dto.UnlockAccountRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountLockPrecedenceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private BalanceCacheService balanceCacheService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private OutboxMasterRepository outboxMasterRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(
                accountRepository,
                customerRepository,
                balanceCacheService,
                kafkaTemplate,
                outboxMasterRepository,
                null
        );

        // Set customer security context
        var auth = new UsernamePasswordAuthenticationToken(
                "cust-1", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void lockAccount_customerLock_shouldFreezeAccount() {
        String accountId = "acct-lock-01";
        AccountMaster account = AccountMaster.builder()
                .accountId(accountId)
                .customerId("cust-1")
                .balanceAmount(new BigDecimal("1000.0000"))
                .currencyCode("PHP")
                .accountStatus("ACTIVE")
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountLockResponse response = accountService.lockAccount(accountId, new LockAccountRequest("Self lock"));

        assertNotNull(response);
        assertEquals("FROZEN", response.accountStatus());
        assertEquals("CUSTOMER", account.getUpdatedBy());
        verify(balanceCacheService).evict(accountId);
    }

    @Test
    void unlockAccount_customerUnlock_whenLockedByCustomer_shouldActivateAccount() {
        String accountId = "acct-lock-02";
        AccountMaster account = AccountMaster.builder()
                .accountId(accountId)
                .customerId("cust-1")
                .balanceAmount(new BigDecimal("1000.0000"))
                .currencyCode("PHP")
                .accountStatus("FROZEN")
                .updatedBy("CUSTOMER")
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(accountRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountLockResponse response = accountService.unlockAccount(accountId, new UnlockAccountRequest("123456"));

        assertNotNull(response);
        assertEquals("ACTIVE", response.accountStatus());
        assertEquals("CUSTOMER", account.getUpdatedBy());
        verify(balanceCacheService).evict(accountId);
    }

    @Test
    void unlockAccount_customerUnlock_whenFrozenByAdmin_shouldRejectDueToAdminPrecedence() {
        String accountId = "acct-lock-03";
        AccountMaster account = AccountMaster.builder()
                .accountId(accountId)
                .customerId("cust-1")
                .balanceAmount(new BigDecimal("1000.0000"))
                .currencyCode("PHP")
                .accountStatus("FROZEN")
                .updatedBy("ADMIN")
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> accountService.unlockAccount(accountId, new UnlockAccountRequest("123456")));

        assertTrue(ex.getMessage().contains("administratively frozen"));
        verify(accountRepository, never()).save(any());
    }

    @Test
    void getPocket_shouldReturnCustomerPocket() {
        String customerId = "cust-1";
        String currency = "USD";
        AccountMaster pocket = AccountMaster.builder()
                .accountId("acct-pocket-usd")
                .customerId(customerId)
                .currencyCode("USD")
                .accountType("SAVINGS")
                .balanceAmount(new BigDecimal("250.0000"))
                .accountStatus("ACTIVE")
                .build();

        when(accountRepository.findByCustomerIdAndCurrencyCode(customerId, currency))
                .thenReturn(Optional.of(pocket));

        AccountDTO dto = accountService.getPocket(customerId, currency);

        assertNotNull(dto);
        assertEquals("acct-pocket-usd", dto.accountId());
        assertEquals("USD", dto.currencyCode());
    }
}
