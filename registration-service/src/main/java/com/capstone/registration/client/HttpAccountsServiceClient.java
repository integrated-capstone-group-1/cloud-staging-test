package com.capstone.registration.client;

import com.capstone.common.dto.AccountDTO;
import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.CreateAccountRequest;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * HTTP REST client for inter-service communication with Accounts Service.
 * Used by registration-service to auto-provision PHP accounts on customer signup.
 * Configured with connection pooling, 3s connect timeout, 5s read timeout.
 */
@Component
@Slf4j
public class HttpAccountsServiceClient implements AccountsServiceClient {

    private final RestClient restClient;
    private final JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper;

    public HttpAccountsServiceClient(
            @Value("${accounts.service.url:http://localhost:8083}") String accountsServiceUrl,
            JwtTokenProvider jwtTokenProvider,
            ObjectMapper objectMapper) {

        this.jwtTokenProvider = jwtTokenProvider;
        this.objectMapper = objectMapper;

        // Configure Java 21 pooled HTTP client with HTTP/1.1 persistent connections
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.restClient = RestClient.builder()
                .baseUrl(accountsServiceUrl)
                .requestFactory(requestFactory)
                .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    try {
                        byte[] bodyBytes = response.getBody().readAllBytes();
                        String responseBody = new String(bodyBytes, StandardCharsets.UTF_8);
                        log.error("Accounts Service error response: {}", responseBody);

                        int statusCode = response.getStatusCode().value();
                        if (statusCode == 404) {
                            throw new ResourceNotFoundException("Accounts Service resource not found: " + responseBody);
                        } else {
                            throw new RuntimeException("Accounts Service returned " + statusCode + ": " + responseBody);
                        }
                    } catch (Exception e) {
                        throw new RuntimeException("Error reading Accounts Service response", e);
                    }
                })
                .build();
    }

    @Override
    public AccountDTO createAccount(CreateAccountRequest request) {
        String authHeader = resolveSystemToken();

        log.debug("Creating account for customerId={}, accountType={}, currencyCode={}",
                request.customerId(), request.accountType(), request.currencyCode());

        ApiResponse<AccountDTO> response = restClient.post()
                .uri("/api/accounts")
                .header("Authorization", authHeader)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<AccountDTO>>() {});

        if (response == null || response.data() == null) {
            throw new IllegalStateException("Empty response from Accounts Service createAccount for customer " + request.customerId());
        }

        log.debug("Account created: accountId={}", response.data().accountId());
        return response.data();
    }

    private String resolveSystemToken() {
        String token = jwtTokenProvider.generateToken(
                "SYSTEM_REG_SERVICE",
                "system-reg@internal.bank",
                List.of("ROLE_ADMIN", "ROLE_INTERNAL")
        );
        return "Bearer " + token;
    }
}
