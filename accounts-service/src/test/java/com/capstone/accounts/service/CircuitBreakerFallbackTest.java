package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.CustomerRepository;
import com.capstone.accounts.repository.OutboxMasterRepository;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerFallbackTest {

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

        var auth = new UsernamePasswordAuthenticationToken(
                "cust-1", null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void getBalanceFallback_shouldReturnCachedBalanceWhenDatabaseDegraded() {
        String accountId = "acct-fallback-01";
        BigDecimal cachedBalance = new BigDecimal("1500.5000");

        when(balanceCacheService.get(accountId)).thenReturn(Optional.of(cachedBalance));

        BigDecimal result = accountService.getBalanceFallback(accountId, new RuntimeException("DB Timeout"));

        assertNotNull(result);
        assertEquals(cachedBalance, result);
        verify(balanceCacheService).get(accountId);
    }

    @Test
    void getBalanceFallback_whenCacheEmpty_shouldThrowIllegalStateException() {
        String accountId = "acct-fallback-02";

        when(balanceCacheService.get(accountId)).thenReturn(Optional.empty());

        assertThrows(IllegalStateException.class,
                () -> accountService.getBalanceFallback(accountId, new RuntimeException("DB Timeout")));
    }
}
