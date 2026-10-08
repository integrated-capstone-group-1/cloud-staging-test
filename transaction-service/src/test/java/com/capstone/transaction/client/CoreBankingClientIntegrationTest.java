package com.capstone.transaction.client;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.exception.InsufficientBalanceException;
import com.capstone.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration Test: SEAM-ROH-01 (DIG-14).
 * Verifies CoreBankingClient Feign/RestClient Bridge Seam:
 * - Issues core settlement payload from transaction-service to CBS (:8083/internal/core/settlement)
 * - Serializes CoreSettlementRequest cleanly
 * - Maps HTTP 200 OK response to CoreSettlementResponse
 * - Translates HTTP 422 INSUFFICIENT_FUNDS into domain InsufficientBalanceException.
 */
class CoreBankingClientIntegrationTest {

    private CoreBankingClient coreBankingClient;
    private MockRestServiceServer mockServer;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(
                "dGhpcy1pcy1hLXZlcnktc2VjdXJlLTI1Ni1iaXQtc2VjcmV0LWtleS1mb3Itand0LXZhbGlkYXRpb24h",
                3600000L
        );
        coreBankingClient = new CoreBankingClient("http://accounts-service:8083", jwtTokenProvider, objectMapper);

        Field restClientField = CoreBankingClient.class.getDeclaredField("restClient");
        restClientField.setAccessible(true);
        RestClient restClient = (RestClient) restClientField.get(coreBankingClient);

        RestClient.Builder builder = restClient.mutate();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClientField.set(coreBankingClient, builder.build());
    }

    @Test
    @DisplayName("SEAM-ROH-01: Correctly serializes CoreSettlementRequest and parses 200 OK response")
    void shouldSerializeAndMapSettlementResponse() {
        CoreSettlementRequest request = new CoreSettlementRequest(
                "txn-5541-uuid",
                "TRANSFER",
                "hold-8812-uuid",
                List.of(
                        new SettlementLegDto("acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR", 1),
                        new SettlementLegDto("acct-maria-eur-01", "CREDIT", new BigDecimal("10.0000"), "EUR", 2)
                )
        );

        String jsonResponse = """
                {
                  "posting_reference_id": "txn-5541-uuid",
                  "core_reference_id": "FT2627800001",
                  "status": "SETTLED",
                  "settled_at": "2026-10-08T10:00:00.000Z"
                }
                """;

        mockServer.expect(MockRestRequestMatchers.requestTo("http://accounts-service:8083/internal/core/settlement"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andExpect(MockRestRequestMatchers.header("Content-Type", MediaType.APPLICATION_JSON_VALUE))
                .andRespond(MockRestResponseCreators.withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        CoreSettlementResponse response = coreBankingClient.settle(request);

        assertThat(response).isNotNull();
        assertThat(response.postingReferenceId()).isEqualTo("txn-5541-uuid");
        assertThat(response.coreReferenceId()).isEqualTo("FT2627800001");
        assertThat(response.status()).isEqualTo("SETTLED");
        mockServer.verify();
    }

    @Test
    @DisplayName("SEAM-ROH-01: Translates CBS HTTP 422 INSUFFICIENT_FUNDS into domain InsufficientBalanceException")
    void shouldTranslate422IntoInsufficientFundsException() {
        CoreSettlementRequest request = new CoreSettlementRequest(
                "txn-5541-fail",
                "TRANSFER",
                null,
                List.of(
                        new SettlementLegDto("acct-juan-eur-01", "DEBIT", new BigDecimal("5000.0000"), "EUR", 1)
                )
        );

        String errorResponse = """
                {
                  "error_code": "INSUFFICIENT_FUNDS",
                  "message": "Account acct-juan-eur-01 has insufficient available balance"
                }
                """;

        mockServer.expect(MockRestRequestMatchers.requestTo("http://accounts-service:8083/internal/core/settlement"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(errorResponse));

        assertThatThrownBy(() -> coreBankingClient.settle(request))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("insufficient available balance");

        mockServer.verify();
    }
}

