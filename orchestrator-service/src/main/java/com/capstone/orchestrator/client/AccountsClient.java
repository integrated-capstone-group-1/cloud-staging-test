package com.capstone.orchestrator.client;

import com.capstone.common.dto.AccountDTO;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.common.security.JwtTokenProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * REST client for Accounts Service (Mock CBS - Tier 4 :8083).
 *
 * [W-H-Y MODEL &amp; TRACEABILITY]:
 * - WHAT: Channel client for querying customer accounts, pockets, and balances.
 * - HOW: Dispatches HTTP GET calls to /api/accounts endpoints using Spring RestClient.
 *        Propagates internal system credentials or current caller authorization.
 * - WHY: Enables Thin Orchestrator to verify account ownership (who owns accountId)
 *        without connecting to CBS Oracle databases directly.
 *
 * INVARIANT: NEVER calls /internal/core/settlement. Orchestrator possesses no settlement privileges.
 */
@Component
@Slf4j
public class AccountsClient {

    private final RestClient restClient;
    private final JwtTokenProvider tokenProvider;

    public AccountsClient(
            @Value("${services.accounts-url:http://localhost:8083}") String accountsUrl,
            JwtTokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
        this.restClient = RestClient.builder()
                .baseUrl(accountsUrl)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (req, resp) -> {
                    if (resp.getStatusCode().value() == 404) {
                        throw new ResourceNotFoundException("Account not found in core banking system");
                    }
                })
                .build();
    }

    /**
     * TRACE: Retrieves a single account by ID to inspect customerId ownership.
     */
    public AccountDTO getAccount(String accountId) {
        String token = resolveSystemToken();
        return restClient.get()
                .uri("/api/accounts/{id}", accountId)
                .header("Authorization", token)
                .retrieve()
                .body(AccountDTO.class);
    }

    /**
     * TRACE: Retrieves all pockets (PHP, USD, EUR) owned by a given customer ID.
     */
    public List<AccountDTO> getCustomerPockets(String customerId) {
        String token = resolveSystemToken();
        try {
            return restClient.get()
                .uri("/api/accounts/customer/{customerId}", customerId)
                .header("Authorization", token)
                .retrieve()
                .body(new ParameterizedTypeReference<List<AccountDTO>>() {});
        } catch (ResourceNotFoundException ex) {
            return Collections.emptyList();
        }
    }

    // ponytail: Internal token generation for read-only ownership validation
    private String resolveSystemToken() {
        return "Bearer " + tokenProvider.generateToken(
                "SYSTEM_ORCHESTRATOR",
                "orchestrator@internal.bank",
                List.of("ROLE_INTERNAL", "ROLE_ADMIN")
        );
    }
}

