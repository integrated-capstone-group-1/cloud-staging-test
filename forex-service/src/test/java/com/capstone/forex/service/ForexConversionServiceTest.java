package com.capstone.forex.service;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.ForexConversionCompletedEvent;
import com.capstone.common.event.ForexConversionRequestedEvent;
import com.capstone.forex.metrics.ForexMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ForexConversionServiceTest {

    @Mock
    private FxRateService fxRateService;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    private ForexMetricsService metricsService;

    private ForexConversionService conversionService;

    @BeforeEach
    void setUp() {
        conversionService = new ForexConversionService(
                fxRateService,
                kafkaTemplate,
                metricsService
        );
    }

    @Test
    @DisplayName("FC-45, FC-46: processConversion computes converted amount and publishes event to Kafka")
    void processConversion_success() {
        UUID txnId = UUID.randomUUID();
        String srcAcct = "acct-php-01";
        String destAcct = "acct-usd-02";
        BigDecimal srcAmount = new BigDecimal("5850.0000");
        BigDecimal rate = new BigDecimal("0.01709402"); // 1 / 58.50

        ForexConversionRequestedEvent request = new ForexConversionRequestedEvent(
                txnId,
                srcAcct,
                destAcct,
                "PHP",
                "USD",
                srcAmount,
                Instant.now()
        );

        when(fxRateService.getExchangeRate("PHP", "USD")).thenReturn(rate);

        ForexConversionCompletedEvent response = conversionService.processConversion(request);

        assertThat(response.txnId()).isEqualTo(txnId);
        assertThat(response.sourceAccountId()).isEqualTo(srcAcct);
        assertThat(response.destAccountId()).isEqualTo(destAcct);
        assertThat(response.sourceCurrency()).isEqualTo("PHP");
        assertThat(response.destCurrency()).isEqualTo("USD");
        assertThat(response.sourceAmount()).isEqualByComparingTo("5850.0000");
        // 5850 * 0.01709402 = 100.000017 -> 100.0000
        assertThat(response.destAmount()).isEqualByComparingTo("100.0000");
        assertThat(response.fxRate()).isEqualByComparingTo(rate);

        // Verify Kafka publication
        ArgumentCaptor<ForexConversionCompletedEvent> eventCaptor = ArgumentCaptor.forClass(ForexConversionCompletedEvent.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.FOREX_CONVERSION_COMPLETED), eq(txnId.toString()), eventCaptor.capture());
        ForexConversionCompletedEvent published = eventCaptor.getValue();
        assertThat(published.txnId()).isEqualTo(txnId);
        assertThat(published.sourceAccountId()).isEqualTo(srcAcct);
        assertThat(published.destAccountId()).isEqualTo(destAcct);
        assertThat(published.destAmount()).isEqualByComparingTo("100.0000");

        // Verify metrics
        verify(metricsService).recordSuccess(txnId.toString(), "PHP", "USD");
        verify(metricsService).clearMdc();
    }
}
