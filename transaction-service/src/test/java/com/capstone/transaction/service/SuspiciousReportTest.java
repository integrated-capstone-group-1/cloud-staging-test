package com.capstone.transaction.service;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.SuspiciousTransactionEvent;
import com.capstone.common.exception.ResourceNotFoundException;
import com.capstone.transaction.dto.SuspiciousReportRequest;
import com.capstone.transaction.dto.SuspiciousReportResponse;
import com.capstone.transaction.entity.oracle.SuspiciousReport;
import com.capstone.transaction.entity.oracle.TransactionMaster;
import com.capstone.transaction.kafka.TransactionEventProducer;
import com.capstone.transaction.repository.oracle.SuspiciousReportRepository;
import com.capstone.transaction.repository.oracle.TransactionMasterRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuspiciousReportTest {

    @Mock
    private SuspiciousReportRepository suspiciousReportRepository;

    @Mock
    private TransactionMasterRepository txnMasterRepository;

    @Mock
    private TransactionEventProducer eventProducer;

    @InjectMocks
    private SuspiciousReportService suspiciousReportService;

    private static final String CUSTOMER_ID = "cust-user-001";
    private static final String TXN_ID = "txn-sus-123";

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                CUSTOMER_ID, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("DIG-91 AC #1 & #2: POST /v1/reports/suspicious creates admin case, emits Kafka event and notifies customer")
    void shouldCreateAdminCaseAndEmitKafkaEvent() {
        TransactionMaster txn = TransactionMaster.builder()
                .txnId(TXN_ID)
                .txnType("TRANSFER")
                .txnStatus("COMMITTED")
                .stage("COMPLETED")
                .mutationAmount(new BigDecimal("25000.0000"))
                .currencyCode("PHP")
                .customerId(CUSTOMER_ID)
                .createdAt(LocalDateTime.now().minusHours(2))
                .build();

        when(txnMasterRepository.findById(TXN_ID)).thenReturn(Optional.of(txn));
        when(suspiciousReportRepository.save(any(SuspiciousReport.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SuspiciousReportRequest request = SuspiciousReportRequest.builder()
                .transactionId(TXN_ID)
                .reason("UNAUTHORIZED_DEBIT")
                .notes("Transaction was executed without my knowledge or OTP consent")
                .build();

        SuspiciousReportResponse response = suspiciousReportService.reportSuspiciousTransaction(request);

        // AC #1: Admin case created
        assertThat(response).isNotNull();
        assertThat(response.getCaseId()).startsWith("CASE-");
        assertThat(response.getTransactionId()).isEqualTo(TXN_ID);
        assertThat(response.getCustomerId()).isEqualTo(CUSTOMER_ID);
        assertThat(response.getStatus()).isEqualTo("OPEN");
        assertThat(response.getReason()).isEqualTo("UNAUTHORIZED_DEBIT");
        assertThat(response.isCustomerNotified()).isTrue();

        ArgumentCaptor<SuspiciousReport> reportCaptor = ArgumentCaptor.forClass(SuspiciousReport.class);
        verify(suspiciousReportRepository).save(reportCaptor.capture());
        SuspiciousReport saved = reportCaptor.getValue();
        assertThat(saved.getCaseId()).isEqualTo(response.getCaseId());
        assertThat(saved.getStatus()).isEqualTo("OPEN");
        assertThat(saved.getAmount()).isEqualByComparingTo(new BigDecimal("25000.0000"));

        // AC #2: Kafka event emitted to fraud topic
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(eventProducer).publishToTopic(eq(KafkaTopics.SUSPICIOUS_TRANSACTION_REPORTED), eq(TXN_ID), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isInstanceOf(SuspiciousTransactionEvent.class);
        SuspiciousTransactionEvent emittedEvent = (SuspiciousTransactionEvent) eventCaptor.getValue();
        assertThat(emittedEvent.caseId()).isEqualTo(response.getCaseId());
        assertThat(emittedEvent.txnId()).isEqualTo(TXN_ID);
        assertThat(emittedEvent.reason()).isEqualTo("UNAUTHORIZED_DEBIT");

        verify(eventProducer).publishToTopic(eq(KafkaTopics.FRAUD_REPORTS), eq(TXN_ID), any());
    }

    @Test
    @DisplayName("DIG-91: Reporting unknown transaction throws ResourceNotFoundException")
    void shouldThrowWhenReportingUnknownTransaction() {
        when(txnMasterRepository.findById("unknown-txn")).thenReturn(Optional.empty());

        SuspiciousReportRequest request = SuspiciousReportRequest.builder()
                .transactionId("unknown-txn")
                .reason("FRAUD")
                .build();

        assertThatThrownBy(() -> suspiciousReportService.reportSuspiciousTransaction(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Transaction unknown-txn not found");

        verify(suspiciousReportRepository, never()).save(any());
        verify(eventProducer, never()).publishToTopic(any(), any(), any());
    }

    @Test
    @DisplayName("DIG-91: Admin query lists reports in dispute queue")
    void shouldReturnAdminCaseQueue() {
        SuspiciousReport r1 = SuspiciousReport.builder()
                .caseId("CASE-001")
                .txnId(TXN_ID)
                .customerId(CUSTOMER_ID)
                .status("OPEN")
                .reason("SUSPECTED_PHISHING")
                .amount(new BigDecimal("1200.0000"))
                .currencyCode("PHP")
                .createdAt(LocalDateTime.now())
                .build();

        when(suspiciousReportRepository.findByStatusOrderByCreatedAtDesc("OPEN")).thenReturn(List.of(r1));

        List<SuspiciousReportResponse> queue = suspiciousReportService.getAdminCaseQueue("OPEN");

        assertThat(queue).hasSize(1);
        assertThat(queue.get(0).getCaseId()).isEqualTo("CASE-001");
        assertThat(queue.get(0).getStatus()).isEqualTo("OPEN");
    }
}

