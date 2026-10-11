package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.entity.Customer;
import com.capstone.accounts.repository.AccountRepository;
import com.capstone.accounts.repository.CustomerRepository;
import com.capstone.common.dto.RecipientLookupResponse;
import com.capstone.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecipientLookupServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private CustomerRepository customerRepository;

    @InjectMocks
    private RecipientLookupService recipientLookupService;

    @Test
    void lookupRecipient_success_shouldReturnMaskedNameAndCurrency() {
        String accountId = "acct-maria-eur-01";
        String customerId = "cust-maria-01";

        AccountMaster account = AccountMaster.builder()
                .accountId(accountId)
                .customerId(customerId)
                .currencyCode("EUR")
                .accountStatus("ACTIVE")
                .build();

        Customer customer = Customer.builder()
                .customerId(customerId)
                .firstName("Maria")
                .lastName("Santos")
                .email("maria.santos@example.com")
                .build();

        when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
        when(customerRepository.findById(customerId)).thenReturn(Optional.of(customer));

        RecipientLookupResponse response = recipientLookupService.lookupRecipient(accountId);

        assertNotNull(response);
        assertEquals(accountId, response.accountId());
        assertEquals("Maria S.", response.maskedName());
        assertEquals("EUR", response.currency());
    }

    @Test
    void lookupRecipient_notFound_shouldThrowResourceNotFoundException() {
        when(accountRepository.findById("unknown-acct")).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> recipientLookupService.lookupRecipient("unknown-acct"));
    }

    @Test
    void maskName_variousNameFormats() {
        assertEquals("Maria S.", recipientLookupService.maskName("Maria", "Santos"));
        assertEquals("Juan D.", recipientLookupService.maskName("Juan", "Dela Cruz"));
        assertEquals("Single", recipientLookupService.maskName("Single", ""));
        assertEquals("L.", recipientLookupService.maskName("", "Last"));
        assertEquals("Anonymous", recipientLookupService.maskName("", ""));
    }
}
