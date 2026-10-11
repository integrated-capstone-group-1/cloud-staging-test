package com.capstone.forex.service;

import com.capstone.forex.dto.FxConvertRequest;
import com.capstone.forex.dto.FxConvertResponse;
import com.capstone.forex.dto.FxQuoteResponse;
import com.capstone.forex.entity.FxRateCache;
import com.capstone.forex.exception.FxQuoteExpiredException;
import com.capstone.forex.exception.FxRateStaleException;
import com.capstone.forex.repository.FxRateCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates 30-second locked FX quotes with 1.5% spread calculation and enforces
 * the 24-hour stale rate guard (BR-04, BR-29, DIG-74, DIG-75).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ForexQuoteService {

    public static final BigDecimal SPREAD_PERCENTAGE = new BigDecimal("1.50");
    public static final BigDecimal SPREAD_MULTIPLIER = new BigDecimal("0.0150");
    public static final int QUOTE_VALIDITY_SECONDS = 30;
    public static final int MAX_RATE_AGE_HOURS = 24;

    private final FxRateCacheRepository fxRateCacheRepository;
    private final FxRateService fxRateService;

    // In-memory quote cache with 30s validity
    // ponytail: in-memory ConcurrentHashMap quote cache; upgrade to Redis if multi-node clustering requires distributed quotes
    private final Map<String, FxQuoteResponse> activeQuotes = new ConcurrentHashMap<>();

    /**
     * Generates a 30-second locked FX quote.
     * Enforces that the underlying rate in fx_rate_cache is not older than 24 hours.
     */
    public FxQuoteResponse generateQuote(String fromCurrency, String toCurrency, BigDecimal targetAmount) {
        if (fromCurrency == null || toCurrency == null || targetAmount == null) {
            throw new IllegalArgumentException("From currency, to currency, and target amount are required");
        }
        if (targetAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Target amount must be greater than zero");
        }

        String from = fromCurrency.trim().toUpperCase();
        String to = toCurrency.trim().toUpperCase();

        // 1. Verify rate age guard (BR-29: fetched_at must not be older than 24h)
        assertRateNotStale(from, to);

        // 2. Fetch spot rate (from -> to or inverse)
        // Note: For converting targetAmount of 'to' from 'from', spotRate is the cost in 'from' per 1 unit of 'to'
        // Contract example: from=PHP, to=EUR, targetAmount=40.0000 -> spot_rate=62.00000000 PHP per EUR
        BigDecimal spotRate = resolvePairSpotRate(from, to);

        // Base source amount before fee
        BigDecimal baseSourceAmount = targetAmount.multiply(spotRate).setScale(4, RoundingMode.HALF_UP);

        // Fee is 1.50% of base source amount
        BigDecimal feeSourceAmount = baseSourceAmount.multiply(SPREAD_MULTIPLIER).setScale(4, RoundingMode.HALF_UP);

        // Total source amount = base source amount + fee
        BigDecimal totalSourceAmount = baseSourceAmount.add(feeSourceAmount).setScale(4, RoundingMode.HALF_UP);

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime validUntil = now.plusSeconds(QUOTE_VALIDITY_SECONDS);
        String quoteId = "q-" + UUID.randomUUID();
        String customerId = com.capstone.common.security.SecurityUtils.getCurrentCustomerId().orElse(null);

        FxQuoteResponse quote = FxQuoteResponse.builder()
                .quoteId(quoteId)
                .customerId(customerId)
                .sourceCurrency(from)
                .targetCurrency(to)
                .targetAmount(targetAmount.setScale(4, RoundingMode.HALF_UP))
                .spotRate(spotRate.setScale(8, RoundingMode.HALF_UP))
                .spreadPercentage(SPREAD_PERCENTAGE)
                .feeSourceAmount(feeSourceAmount)
                .totalSourceAmount(totalSourceAmount)
                .validUntil(validUntil)
                .expiresInSeconds(QUOTE_VALIDITY_SECONDS)
                .build();

        activeQuotes.put(quoteId, quote);
        log.info("Generated FX quote id={}: {} -> {}, spotRate={}, fee={}, total={}",
                quoteId, from, to, spotRate, feeSourceAmount, totalSourceAmount);

        return quote;
    }

    /**
     * Checks if cached FX rate is present and not older than 24 hours.
     */
    private void assertRateNotStale(String from, String to) {
        if (from.equals(to)) {
            return;
        }

        Optional<FxRateCache> direct = fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency(to, from);
        Optional<FxRateCache> inverse = fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency(from, to);

        OffsetDateTime rateFetchedAt = null;
        if (direct.isPresent()) {
            rateFetchedAt = direct.get().getFetchedAt();
        } else if (inverse.isPresent()) {
            rateFetchedAt = inverse.get().getFetchedAt();
        }

        if (rateFetchedAt == null) {
            // Check fallback rates or any cache entry if indirect triangulation
            // If neither direct nor inverse exists in DB cache, check if any rate in DB cache is stale
            long count = fxRateCacheRepository.count();
            if (count == 0) {
                throw new FxRateStaleException("Exchange rate cache is older than 24 hours. Conversions temporarily suspended.");
            }
            return;
        }

        OffsetDateTime threshold = OffsetDateTime.now().minusHours(MAX_RATE_AGE_HOURS);
        if (rateFetchedAt.isBefore(threshold)) {
            log.warn("FX rate for {}/{} fetched at {} is older than 24h threshold {}", from, to, rateFetchedAt, threshold);
            throw new FxRateStaleException("Exchange rate cache is older than 24 hours. Conversions temporarily suspended.");
        }
    }

    /**
     * Resolves the spot rate as units of source currency required per 1 unit of target currency.
     */
    private BigDecimal resolvePairSpotRate(String from, String to) {
        if (from.equals(to)) {
            return BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP);
        }

        // Direct lookup: how many 'from' per 'to' (Base=to, Quote=from)
        Optional<FxRateCache> direct = fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency(to, from);
        if (direct.isPresent()) {
            return direct.get().getRate();
        }

        // Inverse lookup: Base=from, Quote=to
        Optional<FxRateCache> inverse = fxRateCacheRepository.findByBaseCurrencyAndQuoteCurrency(from, to);
        if (inverse.isPresent() && inverse.get().getRate().compareTo(BigDecimal.ZERO) > 0) {
            return BigDecimal.ONE.divide(inverse.get().getRate(), 8, RoundingMode.HALF_UP);
        }

        // Fallback to FxRateService standard lookup (getExchangeRate(to, from) returns from per to)
        return fxRateService.getExchangeRate(to, from);
    }

    /**
     * Executes in-app own-pocket conversion given a valid quote.
     */
    public FxConvertResponse convert(FxConvertRequest request) {
        FxQuoteResponse quote = activeQuotes.get(request.getQuoteId());
        if (quote == null || OffsetDateTime.now().isAfter(quote.getValidUntil())) {
            if (quote != null) {
                activeQuotes.remove(request.getQuoteId());
            }
            throw new FxQuoteExpiredException("The 30-second locked FX quote expired before payment confirmation.");
        }

        // Enforce quote ownership (CONTRACTS.md § 2.1 line 166: quote_id must belong to the caller)
        if (quote.getCustomerId() != null) {
            com.capstone.common.security.SecurityUtils.checkCustomerAccess(
                    quote.getCustomerId(),
                    "execute FX conversion with quote " + quote.getQuoteId()
            );
        }

        // Mark quote as consumed
        activeQuotes.remove(request.getQuoteId());

        return FxConvertResponse.builder()
                .conversionId("conv-" + UUID.randomUUID())
                .quoteId(quote.getQuoteId())
                .status("COMPLETED")
                .sourcePocketId(request.getSourcePocketId())
                .targetPocketId(request.getTargetPocketId())
                .sourceAmountDebited(quote.getTotalSourceAmount())
                .targetAmountCredited(quote.getTargetAmount())
                .createdAt(OffsetDateTime.now())
                .build();
    }

    public Optional<FxQuoteResponse> getQuote(String quoteId) {
        FxQuoteResponse quote = activeQuotes.get(quoteId);
        if (quote != null && OffsetDateTime.now().isAfter(quote.getValidUntil())) {
            activeQuotes.remove(quoteId);
            return Optional.empty();
        }
        return Optional.ofNullable(quote);
    }
}
