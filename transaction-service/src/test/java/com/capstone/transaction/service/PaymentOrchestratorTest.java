package com.capstone.transaction.service;

import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.HoldResponse;
import com.capstone.common.exception.IdempotencyConflictException;
import com.capstone.common.exception.SecurityCheckUnavailableException;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentOrchestratorTest {

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Mock
    private LifecycleService lifecycleService;

    @Mock
    private CoreBankingClient coreBankingClient;

    @Mock
    private RiskScreeningClient riskScreeningClient;

    @InjectMocks
    private PaymentOrchestrator paymentOrchestrator;

    @BeforeEach
    void setUp() {
        lenient().when(txnMasterRepository.save(any(TransactionMaster.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    @DisplayName("Verification 1: Exact match payment succeeds with 2-leg settlement")
    void shouldProcessExactMatchPaymentSuccessfully() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("50.0000"))
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .build();

        when(riskScreeningClient.evaluate(any(RiskEvaluationRequest.class)))
                .thenReturn(RiskEvaluationResponse.builder().score(0.12).decision("ALLOW").build());

        when(lifecycleService.claimForSettlement(anyString())).thenReturn(true);

        when(coreBankingClient.settle(any()))
                .thenReturn(new CoreSettlementResponse("txn-123", "FT2627800001", "SETTLED", LocalDateTime.now()));

        PaymentResponse response = paymentOrchestrator.processPayment(request, "idemp-key-1", "cust-user-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("POSTED");
        assertThat(response.getCoreReferenceId()).isEqualTo("FT2627800001");

        ArgumentCaptor<CoreSettlementRequest> captor = ArgumentCaptor.forClass(CoreSettlementRequest.class);
        verify(coreBankingClient).settle(captor.capture());
        CoreSettlementRequest settlement = captor.getValue();
        assertThat(settlement.legs()).hasSize(2);
        assertThat(settlement.legs().get(0).direction()).isEqualTo("DEBIT");
        assertThat(settlement.legs().get(1).direction()).isEqualTo("CREDIT");
    }

    @Test
    @DisplayName("Verification 2: Cross-currency shortfall constructs 6-leg balanced settlement request")
    void shouldProcessCrossCurrencyShortfallPayment() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("50.0000"))
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .fundingPocketId("acct-juan-php-01")
                .quoteId("q-981-uuid")
                .build();

        when(riskScreeningClient.evaluate(any(RiskEvaluationRequest.class)))
                .thenReturn(RiskEvaluationResponse.builder().score(0.20).decision("ALLOW").build());

        when(lifecycleService.claimForSettlement(anyString())).thenReturn(true);

        when(coreBankingClient.settle(any()))
                .thenReturn(new CoreSettlementResponse("txn-123", "FT2627800001", "SETTLED", LocalDateTime.now()));

        PaymentResponse response = paymentOrchestrator.processPayment(request, "idemp-key-2", "cust-user-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("POSTED");
        assertThat(response.getFundingBreakdown()).isNotNull();

        ArgumentCaptor<CoreSettlementRequest> captor = ArgumentCaptor.forClass(CoreSettlementRequest.class);
        verify(coreBankingClient).settle(captor.capture());
        CoreSettlementRequest settlement = captor.getValue();

        // 6 Legs balanced check per currency
        assertThat(settlement.legs()).hasSize(6);

        BigDecimal eurDebit = settlement.legs().stream()
                .filter(l -> "EUR".equals(l.currency()) && "DEBIT".equals(l.direction()))
                .map(l -> l.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal eurCredit = settlement.legs().stream()
                .filter(l -> "EUR".equals(l.currency()) && "CREDIT".equals(l.direction()))
                .map(l -> l.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(eurDebit).isEqualByComparingTo(eurCredit);

        BigDecimal phpDebit = settlement.legs().stream()
                .filter(l -> "PHP".equals(l.currency()) && "DEBIT".equals(l.direction()))
                .map(l -> l.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal phpCredit = settlement.legs().stream()
                .filter(l -> "PHP".equals(l.currency()) && "CREDIT".equals(l.direction()))
                .map(l -> l.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(phpDebit).isEqualByComparingTo(phpCredit);
    }

    @Test
    @DisplayName("Verification 4: Risk engine timeout fails closed with 503")
    void shouldFailClosedWhenRiskEngineTimesOut() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("50.0000"))
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .build();

        when(riskScreeningClient.evaluate(any(RiskEvaluationRequest.class)))
                .thenThrow(new SecurityCheckUnavailableException("Risk screening SLA breached (> 200ms)"));

        assertThatThrownBy(() -> paymentOrchestrator.processPayment(request, "idemp-timeout", "cust-user-001"))
                .isInstanceOf(SecurityCheckUnavailableException.class)
                .hasMessageContaining("Risk screening SLA breached");

        verify(lifecycleService).markFailed(anyString(), eq("Security check unavailable"));
        verifyNoInteractions(coreBankingClient);
    }

    @Test
    @DisplayName("Risk score > 0.85 places CBS hold and marks status RESERVED")
    void shouldHoldWhenRiskScoreHigh() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("50.0000"))
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .build();

        when(riskScreeningClient.evaluate(any(RiskEvaluationRequest.class)))
                .thenReturn(RiskEvaluationResponse.builder().score(0.92).decision("HOLD").build());

        when(coreBankingClient.placeHold(anyString(), any()))
                .thenReturn(new HoldResponse("hold-8812-uuid", "ACTIVE", new BigDecimal("50.0000")));

        PaymentResponse response = paymentOrchestrator.processPayment(request, "idemp-risk", "cust-user-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("RESERVED");
        assertThat(response.getHoldId()).isEqualTo("hold-8812-uuid");
        verify(lifecycleService).markReserved(anyString(), eq("hold-8812-uuid"));
        verify(coreBankingClient, never()).settle(any());
    }

    @Test
    @DisplayName("REG-ROH-01: Replaying identical request with duplicate idempotency key returns cached response")
    void shouldReplayResponseOnDuplicateKey() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("50.0000"))
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .build();

        TransactionMaster cachedTxn = TransactionMaster.builder()
                .txnId("txn-cached-001")
                .customerId("cust-user-001")
                .creditAccountId("acct-maria-eur-01")
                .mutationAmount(new BigDecimal("50.0000"))
                .currencyCode("EUR")
                .txnStatus("POSTED")
                .stage("COMPLETED")
                .coreReferenceId("FT2627800001")
                .createdAt(LocalDateTime.now())
                .build();

        when(txnMasterRepository.findByCustomerIdAndIdempotencyKey("cust-user-001", "idemp-dup-key"))
                .thenReturn(Optional.of(cachedTxn));

        PaymentResponse response = paymentOrchestrator.processPayment(request, "idemp-dup-key", "cust-user-001");

        assertThat(response).isNotNull();
        assertThat(response.getStatus()).isEqualTo("POSTED");
        assertThat(response.getTransactionId()).isEqualTo("txn-cached-001");
        assertThat(response.getCoreReferenceId()).isEqualTo("FT2627800001");
        verifyNoInteractions(riskScreeningClient);
        verifyNoInteractions(coreBankingClient);
    }

    @Test
    @DisplayName("REG-ROH-01: Duplicate idempotency key with modified payload throws 409 IdempotencyConflictException")
    void shouldRejectModifiedPayloadWithSameKey() {
        PaymentRequest request = PaymentRequest.builder()
                .recipientAccountId("acct-maria-eur-01")
                .amount(new BigDecimal("100.0000")) // Modified amount (was 50.0000)
                .currency("EUR")
                .senderAccountId("acct-juan-eur-01")
                .build();

        TransactionMaster cachedTxn = TransactionMaster.builder()
                .txnId("txn-cached-001")
                .customerId("cust-user-001")
                .creditAccountId("acct-maria-eur-01")
                .mutationAmount(new BigDecimal("50.0000"))
                .currencyCode("EUR")
                .txnStatus("POSTED")
                .build();

        when(txnMasterRepository.findByCustomerIdAndIdempotencyKey("cust-user-001", "idemp-dup-key"))
                .thenReturn(Optional.of(cachedTxn));

        assertThatThrownBy(() -> paymentOrchestrator.processPayment(request, "idemp-dup-key", "cust-user-001"))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("PAYLOAD_MISMATCH");

        verifyNoInteractions(riskScreeningClient);
        verifyNoInteractions(coreBankingClient);
    }
}
