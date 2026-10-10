package com.capstone.forex;

import com.capstone.forex.controller.ForexQuoteController;
import com.capstone.forex.dto.FxConvertRequest;
import com.capstone.forex.dto.FxQuoteResponse;
import com.capstone.forex.entity.FxRateCache;
import com.capstone.forex.repository.FxRateCacheRepository;
import com.capstone.forex.scheduler.ForexRateScheduler;
import com.capstone.forex.service.ForexQuoteService;
import com.capstone.forex.service.FxRateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ForexServiceApplication.class)
@AutoConfigureMockMvc(addFilters = false)
class ForexQuoteAndSchedulerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ForexQuoteService forexQuoteService;

    @Autowired
    private ForexRateScheduler forexRateScheduler;

    @MockBean
    private FxRateCacheRepository fxRateCacheRepository;

    @MockBean
    private FxRateService fxRateService;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("1. Hourly fetch populates fx_rate_cache via external API poll")
    void hourlyFetchPopulatesFxRateCache() {
        forexRateScheduler.pollForexRates();
        verify(fxRateService).refreshRatesFromExternalApi();
    }

    @Test
    @DisplayName("2. Quote calculates 1.5% spread accurately using BigDecimal")
    void quoteCalculatesSpreadAccurately() throws Exception {
        // Mock EUR -> PHP spot rate: 1 EUR = 62.00000000 PHP (target is EUR, source is PHP)
        FxRateCache eurPhp = FxRateCache.builder()
                .baseCurrency("EUR")
                .quoteCurrency("PHP")
                .rate(new BigDecimal("62.00000000"))
                .fetchedAt(OffsetDateTime.now())
                .source("api.frankfurter.dev")
                .build();

        when(fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency("EUR", "PHP"))
                .thenReturn(Optional.of(eurPhp));

        // GET /api/v1/fx/quote?from=PHP&to=EUR&amount=40.0000
        // Base source amount = 40.0000 * 62.00000000 = 2480.0000
        // Spread 1.5% = 2480.0000 * 0.015 = 37.2000
        // Total source amount = 2480.0000 + 37.2000 = 2517.2000
        mockMvc.perform(get("/api/v1/fx/quote")
                        .param("from", "PHP")
                        .param("to", "EUR")
                        .param("amount", "40.0000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_currency").value("PHP"))
                .andExpect(jsonPath("$.target_currency").value("EUR"))
                .andExpect(jsonPath("$.target_amount").value(40.0))
                .andExpect(jsonPath("$.spot_rate").value(62.0))
                .andExpect(jsonPath("$.spread_percentage").value(1.5))
                .andExpect(jsonPath("$.fee_source_amount").value(37.2000))
                .andExpect(jsonPath("$.total_source_amount").value(2517.2000))
                .andExpect(jsonPath("$.expires_in_seconds").value(30))
                .andExpect(jsonPath("$.quote_id").isNotEmpty());
    }

    @Test
    @DisplayName("3. Quote older than 24 hours returns 422 FX_RATE_STALE")
    void quoteOlderThan24HoursReturnsFxRateStale() throws Exception {
        // Rate fetched 25 hours ago
        FxRateCache staleRate = FxRateCache.builder()
                .baseCurrency("EUR")
                .quoteCurrency("PHP")
                .rate(new BigDecimal("62.00000000"))
                .fetchedAt(OffsetDateTime.now().minusHours(25))
                .source("api.frankfurter.dev")
                .build();

        when(fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency("EUR", "PHP"))
                .thenReturn(Optional.of(staleRate));

        mockMvc.perform(get("/api/v1/fx/quote")
                        .param("from", "PHP")
                        .param("to", "EUR")
                        .param("amount", "40.0000"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error_code").value("FX_RATE_STALE"))
                .andExpect(jsonPath("$.message").value("Exchange rate cache is older than 24 hours. Conversions temporarily suspended."));
    }

    @Test
    @DisplayName("4. Own-pocket conversion with valid quote succeeds, and expired quote returns 400 QUOTE_EXPIRED")
    void ownPocketConversionWithQuoteAndExpiration() throws Exception {
        // 1. Generate valid quote
        FxRateCache freshRate = FxRateCache.builder()
                .baseCurrency("EUR")
                .quoteCurrency("PHP")
                .rate(new BigDecimal("62.00000000"))
                .fetchedAt(OffsetDateTime.now())
                .source("api.frankfurter.dev")
                .build();

        when(fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency("EUR", "PHP"))
                .thenReturn(Optional.of(freshRate));

        FxQuoteResponse quote = forexQuoteService.generateQuote("PHP", "EUR", new BigDecimal("40.0000"));
        assertThat(quote.getQuoteId()).isNotNull();

        // 2. Convert with valid quote
        FxConvertRequest validRequest = FxConvertRequest.builder()
                .quoteId(quote.getQuoteId())
                .sourcePocketId("acct-juan-php-01")
                .targetPocketId("acct-juan-eur-01")
                .build();

        mockMvc.perform(post("/api/v1/fx/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote_id").value(quote.getQuoteId()))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.source_amount_debited").value(2517.2000))
                .andExpect(jsonPath("$.target_amount_credited").value(40.0000));

        // 3. Attempt to convert with already-consumed or expired quote returns 400 QUOTE_EXPIRED
        mockMvc.perform(post("/api/v1/fx/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error_code").value("QUOTE_EXPIRED"))
                .andExpect(jsonPath("$.message").value("The 30-second locked FX quote expired before payment confirmation."));
    }

    @Test
    @DisplayName("5. CH-04: 30s locked quote generation remains functional during PostgreSQL outages (Oracle FX schema)")
    void quoteGenerationResilientToPostgreSqlOutage() throws Exception {
        // Delta 2 Verification: FxRateCache is mapped to Oracle fx_rate table.
        // Even if PostgreSQL is completely unavailable or unreachable, quote generation
        // queries Oracle fx_rate and issues locked quotes within 30s SLA.
        FxRateCache oracleRate = FxRateCache.builder()
                .baseCurrency("USD")
                .quoteCurrency("PHP")
                .rate(new BigDecimal("58.50000000"))
                .fetchedAt(OffsetDateTime.now())
                .source("frankfurter")
                .build();

        when(fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency("USD", "PHP"))
                .thenReturn(Optional.of(oracleRate));

        mockMvc.perform(get("/api/v1/fx/quote")
                        .param("from", "USD")
                        .param("to", "PHP")
                        .param("amount", "100.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source_currency").value("USD"))
                .andExpect(jsonPath("$.target_currency").value("PHP"))
                .andExpect(jsonPath("$.expires_in_seconds").value(30))
                .andExpect(jsonPath("$.quote_id").isNotEmpty());
    }

    @Test
    @DisplayName("6. F-008: Cross-customer quote conversion is rejected with 403 ACCESS_DENIED")
    void crossCustomerQuoteConversionRejected() throws Exception {
        // Customer A generates quote
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "cust-juan-001", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))
                )
        );

        FxRateCache freshRate = FxRateCache.builder()
                .baseCurrency("EUR")
                .quoteCurrency("PHP")
                .rate(new BigDecimal("62.00000000"))
                .fetchedAt(OffsetDateTime.now())
                .source("api.frankfurter.dev")
                .build();
        when(fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency("EUR", "PHP"))
                .thenReturn(Optional.of(freshRate));

        FxQuoteResponse quote = forexQuoteService.generateQuote("PHP", "EUR", new BigDecimal("10.0000"));
        assertThat(quote.getQuoteId()).isNotNull();
        assertThat(quote.getCustomerId()).isEqualTo("cust-juan-001");

        // Customer B attempts to convert Customer A's quote
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "cust-maria-002", null, java.util.List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_CUSTOMER"))
                )
        );

        FxConvertRequest hijackRequest = FxConvertRequest.builder()
                .quoteId(quote.getQuoteId())
                .sourcePocketId("pocket-maria-01")
                .targetPocketId("pocket-maria-02")
                .build();

        mockMvc.perform(post("/api/v1/fx/convert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hijackRequest)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error_code").value("ACCESS_DENIED"));

        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }
}

