package com.capstone.forex.scheduler;

import com.capstone.forex.service.FxRateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Hourly scheduler polling external Frankfurter API and caching rates in PostgreSQL (BR-04, DIG-74).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ForexRateScheduler {

    private final FxRateService fxRateService;

    /**
     * Hourly poll: cron = "0 0 * * * *"
     */
    @Scheduled(cron = "${forex.cron:0 0 * * * *}")
    public void pollForexRates() {
        log.info("Running scheduled hourly FX rate poll from Frankfurter API");
        try {
            fxRateService.refreshRatesFromExternalApi();
        } catch (Exception ex) {
            log.warn("Scheduled external FX rate poll failed, retaining last-known good rates: {}", ex.getMessage());
        }
    }
}
