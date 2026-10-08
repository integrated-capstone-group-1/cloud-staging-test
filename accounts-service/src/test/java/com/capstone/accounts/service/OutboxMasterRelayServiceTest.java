package com.capstone.accounts.service;

import com.capstone.accounts.entity.OutboxMaster;
import com.capstone.accounts.repository.OutboxMasterRepository;
import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.PaymentCompletedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OutboxMasterRelayServiceTest {

    private OutboxMasterRepository repository;
    private KafkaTemplate<String, Object> kafkaTemplate;
    private ObjectMapper objectMapper;
    private OutboxMasterRelayService relayService;

    @BeforeEach
    void setUp() {
        repository = mock(OutboxMasterRepository.class);
        kafkaTemplate = mock(KafkaTemplate.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        relayService = new OutboxMasterRelayService(repository, kafkaTemplate, objectMapper);
    }

    @Test
    @DisplayName("Delta 1: SETTLEMENT_COMPLETED produces KafkaEventWrapper<PaymentCompletedEvent> with full leg details to transaction-events")
    void shouldRelaySettlementCompleted_withFullLegDetails() throws Exception {
        String postingRefId = "txn-5541-uuid";
        List<SettlementLegDto> legs = List.of(
                new SettlementLegDto("acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR", 1),
                new SettlementLegDto("acct-maria-eur-01", "CREDIT", new BigDecimal("10.0000"), "EUR", 2)
        );
        CoreSettlementRequest settlementReq = new CoreSettlementRequest(postingRefId, "TRANSFER", "hold-8812-uuid", legs);
        String payloadJson = objectMapper.writeValueAsString(settlementReq);

        OutboxMaster entry = OutboxMaster.builder()
                .outboxId("outbox-101")
                .sourceService("accounts-service")
                .aggregateType("SETTLEMENT")
                .aggregateId(postingRefId)
                .eventType("SETTLEMENT_COMPLETED")
                .payload(payloadJson)
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.findBySourceServiceAndStatusOrderByCreatedAtAsc("accounts-service", "PENDING"))
                .thenReturn(List.of(entry));

        relayService.processOutbox();

        // Verify sent to Kafka topic transaction-events
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.TRANSACTION_EVENTS), eq(postingRefId), payloadCaptor.capture());

        Object sentPayload = payloadCaptor.getValue();
        assertThat(sentPayload).isInstanceOf(KafkaEventWrapper.class);

        @SuppressWarnings("unchecked")
        KafkaEventWrapper<PaymentCompletedEvent> wrapper = (KafkaEventWrapper<PaymentCompletedEvent>) sentPayload;
        assertThat(wrapper.eventType()).isEqualTo("payment.completed");
        assertThat(wrapper.aggregateId()).isEqualTo(postingRefId);
        assertThat(wrapper.payload().txnId()).isEqualTo(postingRefId);
        assertThat(wrapper.payload().legs()).hasSize(2);
        assertThat(wrapper.payload().legs().get(0).leg_no()).isEqualTo(1);
        assertThat(wrapper.payload().legs().get(0).account_id()).isEqualTo("acct-juan-eur-01");
        assertThat(wrapper.payload().legs().get(0).direction()).isEqualTo("DEBIT");
        assertThat(wrapper.payload().legs().get(1).leg_no()).isEqualTo(2);
        assertThat(wrapper.payload().legs().get(1).account_id()).isEqualTo("acct-maria-eur-01");
        assertThat(wrapper.payload().legs().get(1).direction()).isEqualTo("CREDIT");

        // Verify outbox entry marked PUBLISHED
        assertThat(entry.getStatus()).isEqualTo("PUBLISHED");
        assertThat(entry.getPublishedAt()).isNotNull();
        verify(repository).save(entry);
    }

    @Test
    @DisplayName("ACCOUNT_CREATED relays AccountDTO to Kafka account.created")
    void shouldRelayAccountCreated() {
        OutboxMaster entry = OutboxMaster.builder()
                .outboxId("outbox-102")
                .sourceService("accounts-service")
                .aggregateType("ACCOUNT")
                .aggregateId("acct-juan-eur-01")
                .eventType(KafkaTopics.ACCOUNT_CREATED)
                .payload("{\"account_id\":\"acct-juan-eur-01\",\"currency\":\"EUR\"}")
                .status("PENDING")
                .createdAt(LocalDateTime.now())
                .build();

        when(repository.findBySourceServiceAndStatusOrderByCreatedAtAsc("accounts-service", "PENDING"))
                .thenReturn(List.of(entry));

        relayService.processOutbox();

        verify(kafkaTemplate).send(eq(KafkaTopics.ACCOUNT_CREATED), eq("acct-juan-eur-01"), any());
        assertThat(entry.getStatus()).isEqualTo("PUBLISHED");
        verify(repository).save(entry);
    }

    @Test
    @DisplayName("REG-GYM-01: Poll pending outbox entries and mark as PUBLISHED")
    void shouldPollPendingAndMarkPublished() throws Exception {
        shouldRelaySettlementCompleted_withFullLegDetails();
    }
}

