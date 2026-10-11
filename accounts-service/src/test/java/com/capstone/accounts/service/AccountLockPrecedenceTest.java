package com.capstone.accounts.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.common.dto.AccountLockResponse;
import com.capstone.common.dto.UnlockAccountRequest;
import com.capstone.common.exception.ResourceNotFoundException;

/**
 * Regression Test Suite for Account Lock/Freeze Precedence
 * 
 * Tests admin freeze precedence invariants:
 * - Customer-locked accounts CAN be unlocked by customer
 * - Admin-frozen accounts CANNOT be unlocked by customer
 * - Admin freeze takes precedence over customer lock
 * 
 * Related Issues: DIG-68
 * Related BRs: BR-09, TS-07
 */
@DisplayName("AccountService - Admin Freeze Precedence (Regression)")
class AccountLockPrecedenceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private com.capstone.accounts.repository.CustomerRepository customerRepository;

    @Mock
    private BalanceCacheService balanceCacheService;

    @Mock
    private org.springframework.kafka.core.KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private com.capstone.accounts.repository.OutboxMasterRepository outboxMasterRepository;

    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "cust-user-001",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
            )
        );
        MockitoAnnotations.openMocks(this);
        objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
        accountService = new AccountService(
            accountRepository,
            customerRepository,
            balanceCacheService,
            kafkaTemplate,
            outboxMasterRepository,
            objectMapper
        );
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ==========================================
    // REG-YAN-04: Admin Freeze Precedence
    // ==========================================

    @Test
    @DisplayName("REG-YAN-04: Should allow customer to unlock customer-locked account")
    void shouldAllowCustomerToUnlockCustomerLockedAccount() throws Exception {
        // Given: Account locked by customer (UpdatedBy = "CUSTOMER")
        String accountId = "acct-customer-locked-001";
        String customerId = "cust-user-001";
        
        AccountMaster account = createTestAccount(accountId, customerId);
        account.setAccountStatus("FROZEN");
        account.setUpdatedBy("CUSTOMER");  // Locked by customer

        UnlockAccountRequest unlockRequest = new UnlockAccountRequest("123456");

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));
        when(accountRepository.save(any(AccountMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When: Customer attempts to unlock
        AccountLockResponse response = accountService.unlockAccount(accountId, unlockRequest);

        // Then: Unlock succeeds
        assertNotNull(response);
        assertEquals("ACTIVE", response.accountStatus());
        assertEquals("ACTIVE", account.getAccountStatus());

        verify(accountRepository).save(any(AccountMaster.class));
        verify(balanceCacheService).evict(accountId);
    }

    @Test
    @DisplayName("REG-YAN-04: Should reject customer unlock of admin-frozen account")
    void shouldRejectCustomerUnlockOfAdminFrozenAccount() throws Exception {
        // Given: Account frozen by ADMIN (UpdatedBy = "ADMIN")
        String accountId = "acct-admin-frozen-001";
        String customerId = "cust-user-001";
        
        AccountMaster account = createTestAccount(accountId, customerId);
        account.setAccountStatus("FROZEN");
        account.setUpdatedBy("ADMIN");  // Frozen by admin

        UnlockAccountRequest unlockRequest = new UnlockAccountRequest("123456");

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));

        // When: Customer attempts to unlock admin-frozen account
        // Then: Should throw IllegalStateException
        assertThrows(IllegalStateException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequest);
        }, "Customer should not be able to unlock admin-frozen account");

        // Verify no unlock occurred
        verify(accountRepository, never()).save(any(AccountMaster.class));
        verify(balanceCacheService, never()).evict(accountId);
    }

    @Test
    @DisplayName("REG-YAN-04: Should verify admin freeze takes precedence over customer lock")
    void shouldVerifyAdminFreezePreference() throws Exception {
        // Given: Account that could be either customer-locked or admin-frozen
        String accountId = "acct-precedence-test";
        String customerId = "cust-user-001";
        
        AccountMaster account = createTestAccount(accountId, customerId);
        account.setAccountStatus("FROZEN");
        account.setUpdatedBy("ADMIN");  // Admin freeze

        UnlockAccountRequest unlockRequest = new UnlockAccountRequest("correct-otp");

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));

        // When: Attempt unlock with correct OTP
        // Then: Should still fail because admin freeze takes precedence
        assertThrows(IllegalStateException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequest);
        }, "Admin freeze should take precedence regardless of OTP correctness");
    }

    @Test
    @DisplayName("REG-YAN-04: Should allow admin to freeze an active account")
    void shouldAllowAdminToFreezeActiveAccount() throws Exception {
        // Given: Active account
        String accountId = "acct-active-001";
        String customerId = "cust-user-001";
        
        AccountMaster account = createTestAccount(accountId, customerId);
        account.setAccountStatus("ACTIVE");

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));
        when(accountRepository.save(any(AccountMaster.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        // When: Admin freezes the account
        com.capstone.common.dto.AccountDTO response = accountService.updateAccountStatus(accountId, "FROZEN");

        // Then: Account is frozen with UpdatedBy = "ADMIN"
        assertNotNull(response);
        assertEquals("FROZEN", response.accountStatus());
        assertEquals("ADMIN", account.getUpdatedBy());

        verify(accountRepository).save(any(AccountMaster.class));
        verify(balanceCacheService).evict(accountId);
    }

    @Test
    @DisplayName("REG-YAN-04: Should require valid OTP for customer unlock")
    void shouldRequireValidOtpForCustomerUnlock() throws Exception {
        // Given: Customer-locked account
        String accountId = "acct-otp-test";
        String customerId = "cust-user-001";
        
        AccountMaster account = createTestAccount(accountId, customerId);
        account.setAccountStatus("FROZEN");
        account.setUpdatedBy("CUSTOMER");

        // When: Attempt unlock with null OTP
        UnlockAccountRequest unlockRequestNull = new UnlockAccountRequest(null);

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));

        // Then: Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequestNull);
        }, "Valid OTP is required");

        // When: Attempt unlock with blank OTP
        UnlockAccountRequest unlockRequestBlank = new UnlockAccountRequest("   ");

        // Then: Should throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequestBlank);
        }, "Valid OTP is required");

        // Verify no unlock occurred
        verify(accountRepository, never()).save(any(AccountMaster.class));
    }

    @Test
    @DisplayName("REG-YAN-04: Should maintain admin freeze status across unlock attempts")
    void shouldMaintainAdminFreezeStatusAcrossUnlockAttempts() throws Exception {
        // Given: Admin-frozen account
        String accountId = "acct-freeze-maintain";
        String customerId = "cust-user-001";
        
        AccountMaster account = createTestAccount(accountId, customerId);
        account.setAccountStatus("FROZEN");
        account.setUpdatedBy("ADMIN");

        UnlockAccountRequest unlockRequest1 = new UnlockAccountRequest("otp-123");
        UnlockAccountRequest unlockRequest2 = new UnlockAccountRequest("otp-456");

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.of(account));

        // When: Multiple unlock attempts are made
        assertThrows(IllegalStateException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequest1);
        });

        assertThrows(IllegalStateException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequest2);
        });

        // Then: Account remains frozen and unchanged
        assertEquals("FROZEN", account.getAccountStatus());
        assertEquals("ADMIN", account.getUpdatedBy());

        // Verify no save occurred
        verify(accountRepository, never()).save(any(AccountMaster.class));
    }

    @Test
    @DisplayName("REG-YAN-04: Should throw exception when unlocking non-existent account")
    void shouldThrowExceptionWhenUnlockingNonExistentAccount() throws Exception {
        // Given: Non-existent account
        String accountId = "acct-nonexistent";

        when(accountRepository.findById(accountId))
            .thenReturn(Optional.empty());

        UnlockAccountRequest unlockRequest = new UnlockAccountRequest("123456");

        // When: Unlock is attempted
        // Then: Should throw ResourceNotFoundException
        assertThrows(ResourceNotFoundException.class, () -> {
            accountService.unlockAccount(accountId, unlockRequest);
        }, "Should throw exception for non-existent account");

        verify(accountRepository, never()).save(any(AccountMaster.class));
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    private AccountMaster createTestAccount(String accountId, String customerId) {
        AccountMaster account = new AccountMaster();
        account.setAccountId(accountId);
        account.setAccountNumber("ACC-" + accountId);
        account.setCustomerId(customerId);
        account.setBalanceAmount(new BigDecimal("5000.00"));
        account.setHeldAmount(BigDecimal.ZERO);
        account.setAccountStatus("ACTIVE");
        account.setAdminFrozen("N");
        account.setCustomerLocked("N");
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        account.setUpdatedBy("SYSTEM");
        account.setCurrencyCode("PHP");
        account.setAccountType("SAVINGS");
        account.setIsPrimary("Y");
        return account;
    }
}
