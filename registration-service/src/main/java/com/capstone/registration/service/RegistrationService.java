package com.capstone.registration.service;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Handles customer self-registration with automatic primary PHP pocket provisioning.
 * 
 * BR-16: Registration auto-provisions one primary PHP account per customer
 * BR-07: BCrypt password hashing
 * Delta 1: Single-database fallback (Oracle XE) for customer_master and app_user_master
 * Delta 2: Transactional rollback guarantee if CBS account creation fails
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegistrationService {

    private final CustomerRepository customerRepository;
    private final AppUserRepository appUserRepository;
    private final PasswordEncoder passwordEncoder;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final AccountsServiceClient accountsServiceClient;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * Atomically inserts into CUSTOMER_MASTER and APP_USER_MASTER,
     * and auto-provisions one primary PHP pocket in CBS.
     * 
     * Customer record is committed before the inter-service call to accounts-service
     * so that foreign-key validation succeeds in Oracle. If CBS provisioning fails,
     * compensating rollback deletes the newly inserted records.
     */
    public RegisterResponse register(RegisterRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException(
                    "A customer with email '" + request.email() + "' already exists");
        }

        String customerId = UUID.randomUUID().toString();
        String userId     = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        Customer customer = Customer.builder()
                .customerId(customerId)
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .contactNo(request.contactNo())        // nullable
                .birthDate(request.birthDate())        // nullable
                .idType(request.idType())              // nullable
                .idNumber(request.idNumber())          // nullable
                .residentialAddress(request.address()) // nullable
                .createdAt(now)
                .createdBy("SYSTEM")
                .build();

        // username defaults to email; can be overridden later via a profile endpoint
        AppUser appUser = AppUser.builder()
                .userId(userId)
                .customerId(customerId)
                .username(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .role(request.role() != null ? request.role() : "CUSTOMER")
                .activeStatus("ACTIVE")
                .createdAt(now)
                .createdBy("SYSTEM")
                .build();

        if (transactionManager != null) {
            new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                customerRepository.saveAndFlush(customer);
                appUserRepository.saveAndFlush(appUser);
                return null;
            });
        } else {
            customerRepository.save(customer);
            appUserRepository.save(appUser);
        }

        // BR-16 / Delta 2: Auto-provision primary PHP pocket in CBS.
        // If CBS provisioning fails, compensation rolls back customer and app_user records cleanly.
        try {
            provisionPrimaryAccount(customerId);
        } catch (Exception e) {
            if (transactionManager != null) {
                new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                    appUserRepository.deleteById(userId);
                    customerRepository.deleteById(customerId);
                    return null;
                });
            }
            throw e;
        }

        RegisterResponse response = new RegisterResponse(
                customerId, customer.getFirstName(), customer.getLastName(), customer.getEmail());

        // Publish customer.registered event ONLY after DB writes and account provisioning succeed
        kafkaTemplate.send(KafkaTopics.CUSTOMER_REGISTERED, customerId, response)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.warn("Failed to publish customer.registered for customerId={}", customerId, ex);
                    } else {
                        log.debug("Published customer.registered for customerId={}", customerId);
                    }
                });

        return response;
    }

    /**
     * Auto-provisions one primary PHP savings account for the newly registered customer.
     * Uses system-to-system authentication with internal JWT.
     * 
     * @param customerId the UUID of the newly created customer
     */
    private void provisionPrimaryAccount(String customerId) {
        try {
            CreateAccountRequest accountRequest = new CreateAccountRequest(
                    customerId,
                    "SAVINGS",
                    "PHP"
            );

            AccountDTO account = accountsServiceClient.createAccount(accountRequest);
            if (account == null || account.accountId() == null) {
                throw new IllegalStateException("AccountsServiceClient returned null account for customerId=" + customerId);
            }
            log.info("Auto-provisioned primary PHP account for customerId={}, accountId={}",
                    customerId, account.accountId());

        } catch (Exception e) {
            log.error("Failed to auto-provision primary PHP account for customerId={}. " +
                    "Rolling back registration transaction: {}", customerId, e.getMessage());
            throw new IllegalStateException("Failed to auto-provision primary PHP account in CBS: " + e.getMessage(), e);
        }
    }
}
