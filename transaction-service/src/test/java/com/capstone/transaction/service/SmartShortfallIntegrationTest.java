package com.capstone.transaction.service;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.transaction.client.CoreBankingClient;
import com.capstone.transaction.client.RiskScreeningClient;
import com.capstone.transaction.dto.PaymentRequest;
import com.capstone.transaction.dto.PaymentResponse;
import com.capstone.transaction.dto.risk.RiskEvaluationRequest;
import com.capstone.transaction.dto.risk.RiskEvaluationResponse;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Integration Test: SEAM-ROH-04 (DIG-73, BR-28).
 * Verifies Smart Shortfall Multi-Currency Orchestration Seam:
 * - Juan pays €50.00 to Maria
 * - Primary pocket acct-juan-eur-01 funds €10.00
 * - Shortfall €40.00 funded from acct-juan-php-01 (debited ₱2,480.00 based on rate 62.0)
 * - Status returns POSTED
 * - Verifies CBS core settlement executes exactly 6 balanced legs atomically.
 */
@ExtendWith(MockitoExtension.class)
class SmartShortfallIntegrationTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Mock
    private LifecycleService lifecycleService;

    @Mock
    private CoreBankingClient coreBankingClient;

    @Mock
    private RiskScreeningClient riskScreeningClient;

    @Mock
    private IdempotencyService idempotencyService;

    @InjectMocks
    private PaymentOrchestrator paymentOrchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(txnMasterRepository.save(any(TransactionMaster.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(lifecycleService.markAuthorized(anyString())).thenReturn(true);
        lenient().when(idempotencyService.tryLock(any(), any())).thenReturn(true);
    }

    @Test
    @DisplayName("SEAM-ROH-04: Smart Shortfall orchestrates 6 balanced legs and funding breakdown")
    void verifySmartShortfallOrchestration() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("50.0000"))
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .fundingPocketId("acct-juan-php-01")
                .quoteId("q-valid-uuid")
                .build();

        when(riskScreeningClient.evaluate(any(RiskEvaluationRequest.class)))
                .thenReturn(RiskEvaluationResponse.builder().score(0.15).decision("ALLOW").build());

        when(lifecycleService.claimForSettlement(anyString())).thenReturn(true);

        when(coreBankingClient.settle(any(CoreSettlementRequest.class)))
                .thenReturn(new CoreSettlementResponse("TXN-SF-101", "FT2627800001", "SETTLED", LocalDateTime.now()));

        PaymentResponse response = paymentOrchestrator.processPayment(
                request, "e8a91176-0000-4000-8000-000000000001", "cust-user-001");

        // Verify Step 2: HTTP status POSTED
        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("POSTED");
        assertThat(response.getCoreReferenceId()).isEqualTo("FT2627800001");

        // Verify Step 3: Funding breakdown details
        assertThat(response.getFundingBreakdown()).isNotNull();
        assertThat(response.getFundingBreakdown().getDirectDebitedAmount()).isEqualByComparingTo(new BigDecimal("10.0000"));
        assertThat(response.getFundingBreakdown().getConvertedShortfallAmount()).isEqualByComparingTo(new BigDecimal("40.0000"));
        assertThat(response.getFundingBreakdown().getFxFeeAmount()).isEqualByComparingTo(new BigDecimal("37.2000"));
        assertThat(response.getFundingBreakdown().getFundingDebitedAmount()).isEqualByComparingTo(new BigDecimal("2517.2000"));

        // Verify Step 4: CBS settlement executed all 6 legs atomically
        ArgumentCaptor<CoreSettlementRequest> captor = ArgumentCaptor.forClass(CoreSettlementRequest.class);
        verify(coreBankingClient).settle(captor.capture());
        CoreSettlementRequest settlement = captor.getValue();

        assertThat(settlement.legs()).hasSize(6);

        // Assert currency balances across EUR and PHP legs
        BigDecimal eurDebits = settlement.legs().stream()
                .filter(l -> "EUR".equals(l.currency()) && "DEBIT".equals(l.direction()))
                .map(SettlementLegDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal eurCredits = settlement.legs().stream()
                .filter(l -> "EUR".equals(l.currency()) && "CREDIT".equals(l.direction()))
                .map(SettlementLegDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(eurDebits).isEqualByComparingTo(eurCredits);

        BigDecimal phpDebits = settlement.legs().stream()
                .filter(l -> "PHP".equals(l.currency()) && "DEBIT".equals(l.direction()))
                .map(SettlementLegDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal phpCredits = settlement.legs().stream()
                .filter(l -> "PHP".equals(l.currency()) && "CREDIT".equals(l.direction()))
                .map(SettlementLegDto::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(phpDebits).isEqualByComparingTo(phpCredits);
    }
}

