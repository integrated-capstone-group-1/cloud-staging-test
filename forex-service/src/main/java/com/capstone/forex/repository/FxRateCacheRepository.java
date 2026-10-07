package com.capstone.forex.repository;

import com.capstone.forex.entity.FxRateCache;
import com.capstone.forex.entity.FxRateId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FxRateCacheRepository extends JpaRepository<FxRateCache, FxRateId> {

    Optional<FxRateCache> findByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);
}
