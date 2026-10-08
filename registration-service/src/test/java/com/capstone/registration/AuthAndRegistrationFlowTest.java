package com.capstone.registration;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.dto.AccountDTO;
import com.capstone.common.dto.CreateAccountRequest;
import com.capstone.common.dto.RegisterRequest;
import com.capstone.common.dto.RegisterResponse;
import com.capstone.common.exception.DuplicateResourceException;
import com.capstone.registration.client.AccountsServiceClient;
import com.capstone.registration.entity.AppUser;
import com.capstone.registration.entity.Customer;
import com.capstone.registration.repository.AppUserRepository;
import com.capstone.registration.repository.CustomerRepository;
import com.capstone.registration.service.RegistrationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit & Integration test for T-006: Customer Onboarding & Registration Slice
 * BR-16, BR-07, TR-S01
 */
@ExtendWith(MockitoExtension.class)
class AuthAndRegistrationFlowTest {

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private AppUserRepository appUserRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Mock
    private AccountsServiceClient accountsServiceClient;

    @InjectMocks
    private RegistrationService registrationService;

    private static final String EMAIL = "maria@example.com";
    private static final String PASSWORD = "SecurePassword123!";
    private static final String HASHED_PASSWORD = "$2a$10$hashedBCryptPasswordSample";

    @Test
    @DisplayName("BR-16 / BR-07: Registration creates user, hashes password, and auto-provisions PHP account")
    void testRegistration_createsUserAndProvisionsPhpAccount() {
        when(customerRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED_PASSWORD);
        when(kafkaTemplate.send(eq(KafkaTopics.CUSTOMER_REGISTERED), anyString(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        AccountDTO provisionedAccount = new AccountDTO(
                "acct-php-01", "cust-generated-uuid", "SAVINGS", "ACTIVE",
                BigDecimal.ZERO, "PHP", LocalDateTime.now(), BigDecimal.ZERO, "Y"
        );
        when(accountsServiceClient.createAccount(any(CreateAccountRequest.class)))
                .thenReturn(provisionedAccount);

        RegisterRequest request = new RegisterRequest(
                "Maria", "Santos", EMAIL, "+63-917-123-4567",
                LocalDate.of(1995, 3, 20), PASSWORD, "CUSTOMER",
                "PASSPORT", "P1234567", "Manila, Philippines"
        );

        RegisterResponse response = registrationService.register(request);

        assertThat(response.email()).isEqualTo(EMAIL);
        assertThat(response.firstName()).isEqualTo("Maria");
        assertThat(response.lastName()).isEqualTo("Santos");
        assertThat(response.customerId()).isNotNull();

        // 1. Verify Customer entity saved
        ArgumentCaptor<Customer> customerCaptor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).save(customerCaptor.capture());
        Customer savedCustomer = customerCaptor.getValue();
        assertThat(savedCustomer.getEmail()).isEqualTo(EMAIL);
        assertThat(savedCustomer.getFirstName()).isEqualTo("Maria");

        // 2. Verify AppUser entity saved with BCrypt hash
        ArgumentCaptor<AppUser> userCaptor = ArgumentCaptor.forClass(AppUser.class);
        verify(appUserRepository).save(userCaptor.capture());
        AppUser savedUser = userCaptor.getValue();
        assertThat(savedUser.getUsername()).isEqualTo(EMAIL);
        assertThat(savedUser.getPasswordHash()).isEqualTo(HASHED_PASSWORD);
        assertThat(savedUser.getActiveStatus()).isEqualTo("ACTIVE");
        assertThat(savedUser.getRole()).isEqualTo("CUSTOMER");

        // 3. Verify auto-provisioning of primary PHP account
        ArgumentCaptor<CreateAccountRequest> acctCaptor = ArgumentCaptor.forClass(CreateAccountRequest.class);
        verify(accountsServiceClient).createAccount(acctCaptor.capture());
        CreateAccountRequest capturedAcctReq = acctCaptor.getValue();
        assertThat(capturedAcctReq.currencyCode()).isEqualTo("PHP");
        assertThat(capturedAcctReq.accountType()).isEqualTo("SAVINGS");
        assertThat(capturedAcctReq.customerId()).isEqualTo(response.customerId());
    }

    @Test
    @DisplayName("BR-16: Duplicate email registration throws DuplicateResourceException (409)")
    void testRegistration_duplicateEmail_throwsDuplicateResourceException() {
        when(customerRepository.existsByEmail(EMAIL)).thenReturn(true);

        RegisterRequest request = new RegisterRequest(
                "Maria", "Santos", EMAIL, null,
                LocalDate.of(1995, 3, 20), PASSWORD, null,
                null, null, null
        );

        assertThatThrownBy(() -> registrationService.register(request))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("already exists");

        verify(customerRepository, never()).save(any());
        verify(appUserRepository, never()).save(any());
        verify(accountsServiceClient, never()).createAccount(any());
    }

    @Test
    @DisplayName("Delta 2: CBS primary account auto-provisioning failure aborts registration without Kafka publish")
    void testRegistration_cbsAccountProvisioningFails_rollsBackAndThrowsException() {
        when(customerRepository.existsByEmail(EMAIL)).thenReturn(false);
        when(passwordEncoder.encode(PASSWORD)).thenReturn(HASHED_PASSWORD);
        when(accountsServiceClient.createAccount(any(CreateAccountRequest.class)))
                .thenThrow(new RuntimeException("CBS Accounts Service unavailable (Connection refused)"));

        RegisterRequest request = new RegisterRequest(
                "Maria", "Santos", EMAIL, "+63-917-123-4567",
                LocalDate.of(1995, 3, 20), PASSWORD, "CUSTOMER",
                "PASSPORT", "P1234567", "Manila, Philippines"
        );

        assertThatThrownBy(() -> registrationService.register(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to auto-provision primary PHP account in CBS");

        // Verify Kafka event was NOT published due to provisioning failure
        verify(kafkaTemplate, never()).send(anyString(), anyString(), any());
    }
}
