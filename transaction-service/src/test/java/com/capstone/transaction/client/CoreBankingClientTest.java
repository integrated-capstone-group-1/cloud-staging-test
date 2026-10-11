package com.capstone.transaction.client;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.CreateHoldRequest;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.dto.ReleaseHoldResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CoreBankingClientTest {

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
        coreBankingClient = new CoreBankingClient("http://localhost:8083", jwtTokenProvider, objectMapper);

        // Bind MockRestServiceServer to CoreBankingClient's RestClient
        Field restClientField = CoreBankingClient.class.getDeclaredField("restClient");
        restClientField.setAccessible(true);
        RestClient restClient = (RestClient) restClientField.get(coreBankingClient);

        RestClient.Builder builder = restClient.mutate();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        restClientField.set(coreBankingClient, builder.build());
    }

    @Test
    @DisplayName("DIG-14: settle() successfully bridges settlement payload to CBS")
    void shouldSettleSuccessfully() {
        CoreSettlementRequest request = new CoreSettlementRequest(
                "txn-101",
                "TRANSFER",
                null,
                List.of(
                        new SettlementLegDto("acct-1", "DEBIT", new BigDecimal("100.0000"), "PHP", 1),
                        new SettlementLegDto("acct-2", "CREDIT", new BigDecimal("100.0000"), "PHP", 2)
                )
        );

        String jsonResponse = """
                {
                  "posting_reference_id": "txn-101",
                  "core_reference_id": "FT2627800001",
                  "status": "SETTLED",
                  "settled_at": "2026-10-05T11:15:02.000Z"
                }
                """;

        mockServer.expect(MockRestRequestMatchers.requestTo("http://localhost:8083/internal/core/settlement"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        CoreSettlementResponse response = coreBankingClient.settle(request);

        assertThat(response).isNotNull();
        assertThat(response.coreReferenceId()).isEqualTo("FT2627800001");
        assertThat(response.status()).isEqualTo("SETTLED");
        mockServer.verify();
    }

    @Test
    @DisplayName("DIG-14: placeHold() successfully requests CBS amount hold")
    void shouldPlaceHoldSuccessfully() {
        CreateHoldRequest request = new CreateHoldRequest(
                new BigDecimal("50.0000"),
                "EUR",
                "RISK",
                24,
                "Admin risk hold"
        );

        String jsonResponse = """
                {
                  "hold_id": "hold-8812-uuid",
                  "status": "ACTIVE",
                  "held_amount": 50.0000
                }
                """;

        mockServer.expect(MockRestRequestMatchers.requestTo("http://localhost:8083/internal/accounts/acct-juan-eur-01/holds"))
                .andExpect(MockRestRequestMatchers.method(HttpMethod.POST))
                .andRespond(MockRestResponseCreators.withSuccess(jsonResponse, MediaType.APPLICATION_JSON));

        HoldResponse response = coreBankingClient.placeHold("acct-juan-eur-01", request);

        assertThat(response).isNotNull();
        assertThat(response.holdId()).isEqualTo("hold-8812-uuid");
        assertThat(response.status()).isEqualTo("ACTIVE");
        mockServer.verify();
    }
}
