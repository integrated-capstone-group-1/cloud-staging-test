package com.capstone.notification.kafka;

import com.capstone.common.event.KafkaEventWrapper;
import com.capstone.common.event.LegDTO;
import com.capstone.common.event.PaymentCompletedEvent;
import com.capstone.common.event.TransactionCompletedEvent;
import com.capstone.common.event.TransactionCreatedEvent;
import com.capstone.common.event.TransactionFailedEvent;
import com.capstone.notification.service.NotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransactionEventConsumerTest {

    private NotificationService notificationService;
    private ObjectMapper objectMapper;
    private TransactionEventConsumer consumer;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        consumer = new TransactionEventConsumer(notificationService, objectMapper);
    }

    @Test
    @DisplayName("Delta 2: payment.completed settlement event triggers customer notification")
    void shouldDispatchNotification_onPaymentCompletedEvent() {
        String txnId = "txn-5541-uuid";
        LegDTO leg = new LegDTO(1, "acct-juan-eur-01", "DEBIT", new BigDecimal("10.0000"), "EUR");
        PaymentCompletedEvent payload = new PaymentCompletedEvent(txnId, "FT2627800001", "cust-user-001", List.of(leg));

        KafkaEventWrapper<PaymentCompletedEvent> event = new KafkaEventWrapper<>(
                "evt-7712-uuid", "TRANSACTION", txnId, "payment.completed", Instant.now(), payload);

        consumer.onMessage(event);

        verify(notificationService).notifySettlementCompleted(any(PaymentCompletedEvent.class));
    }

    @Test
    @DisplayName("TransactionCompletedEvent triggers notifyCompleted")
    void shouldDispatchNotification_onTransactionCompletedEvent() {
        TransactionCompletedEvent event = new TransactionCompletedEvent(
                UUID.randomUUID(),
                "acct-1",
                "acct-2",
                "TRANSFER",
                new BigDecimal("500.00"),
                new BigDecimal("4500.00"),
                Instant.now()
        );

        consumer.onMessage(event);

        verify(notificationService).notifyCompleted(any(TransactionCompletedEvent.class));
    }

    @Test
    @DisplayName("TransactionFailedEvent triggers notifyFailed")
    void shouldDispatchNotification_onTransactionFailedEvent() {
        TransactionFailedEvent event = new TransactionFailedEvent(
                UUID.randomUUID(),
                "acct-1",
                "acct-2",
                "TRANSFER",
                new BigDecimal("500.00"),
                "INSUFFICIENT_FUNDS",
                Instant.now()
        );

        consumer.onMessage(event);

        verify(notificationService).notifyFailed(any(TransactionFailedEvent.class));
    }

    @Test
    @DisplayName("TransactionCreatedEvent triggers notifyCreated")
    void shouldDispatchNotification_onTransactionCreatedEvent() {
        TransactionCreatedEvent event = new TransactionCreatedEvent(
                UUID.randomUUID(),
                "acct-1",
                "acct-2",
                "TRANSFER",
                new BigDecimal("500.00"),
                Instant.now()
        );

        consumer.onMessage(event);

        verify(notificationService).notifyCreated(any(TransactionCreatedEvent.class));
    }
}

