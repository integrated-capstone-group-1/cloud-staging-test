package com.capstone.forex.service;

import com.capstone.common.constants.KafkaTopics;
import com.capstone.common.event.ForexConversionCompletedEvent;
import com.capstone.common.event.ForexConversionRequestedEvent;
import com.capstone.forex.metrics.ForexMetricsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Computes currency conversion from cached FX rates and directly publishes
 * FOREX_CONVERSION_COMPLETED to Kafka (FC-45, FC-46).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForexConversionService {

    private final FxRateService fxRateService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ForexMetricsService metricsService;

    public ForexConversionCompletedEvent processConversion(ForexConversionRequestedEvent request) {
        String txnId = request.txnId().toString();
        log.info("Processing ForEx conversion for txnId={}: {} {} -> {}",
                txnId, request.sourceAmount(), request.sourceCurrency(), request.destCurrency());

        try {
            BigDecimal rate = fxRateService.getExchangeRate(request.sourceCurrency(), request.destCurrency());
            BigDecimal destAmount = request.sourceAmount()
                    .multiply(rate)
                    .setScale(4, RoundingMode.HALF_UP);

            ForexConversionCompletedEvent completedEvent = new ForexConversionCompletedEvent(
                    request.txnId(),
                    request.sourceAccountId(),
                    request.destAccountId(),
                    request.sourceCurrency().toUpperCase(),
                    request.destCurrency().toUpperCase(),
                    request.sourceAmount(),
                    rate,
                    destAmount,
                    Instant.now()
            );

            kafkaTemplate.send(KafkaTopics.FOREX_CONVERSION_COMPLETED, txnId, completedEvent);

            log.info("ForEx conversion completed for txnId={}: destAmount={} (rate={})",
                    txnId, destAmount, rate);

            metricsService.recordSuccess(txnId, request.sourceCurrency(), request.destCurrency());
            return completedEvent;

        } catch (Exception ex) {
            log.error("Failed ForEx conversion for txnId={}: {}", txnId, ex.getMessage(), ex);
            metricsService.recordFailure(txnId, request.sourceCurrency(), request.destCurrency(), ex.getClass().getSimpleName());
            throw new RuntimeException("ForEx conversion failed", ex);
        } finally {
            metricsService.clearMdc();
        }
    }
}
