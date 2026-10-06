package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.entity.Customer;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.CustomerRepository;
import com.capstone.common.dto.RecipientLookupResponse;
import com.capstone.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class RecipientLookupService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public RecipientLookupResponse lookupRecipient(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("Account ID must not be blank");
        }

        AccountMaster account = accountRepository.findById(accountId.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Account " + accountId + " not found"));

        Customer customer = customerRepository.findById(account.getCustomerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer for account " + accountId + " not found"));

        String maskedName = maskName(customer.getFirstName(), customer.getLastName());
        String currency = account.getCurrencyCode() != null ? account.getCurrencyCode().trim() : "";

        return new RecipientLookupResponse(account.getAccountId(), maskedName, currency);
    }

    public String maskName(String firstName, String lastName) {
        String cleanFirst = firstName != null ? firstName.trim() : "";
        String cleanLast = lastName != null ? lastName.trim() : "";

        if (cleanFirst.isEmpty() && cleanLast.isEmpty()) {
            return "Anonymous";
        }
        if (cleanLast.isEmpty()) {
            return cleanFirst;
        }
        if (cleanFirst.isEmpty()) {
            return cleanLast.charAt(0) + ".";
        }
        return cleanFirst + " " + cleanLast.charAt(0) + ".";
    }
}
