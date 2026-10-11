package com.capstone.registration.client;

import com.capstone.common.dto.AccountDTO;
import com.capstone.common.dto.CreateAccountRequest;

/**
 * Client interface for account operations against Accounts Service.
 * Used by registration-service to auto-provision primary PHP pocket.
 */
public interface AccountsServiceClient {

    /**
     * Creates a new account for a customer.
     *
     * @param request the account creation request (customerId, accountType, currencyCode)
     * @return the created AccountDTO with accountId and metadata
     */
    AccountDTO createAccount(CreateAccountRequest request);
}