package com.capstone.transaction.repository.oracle;

import com.capstone.transaction.entity.oracle.CardMaster;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Targets CARD_MASTER via the Oracle primary DataSource.
 * String PK: card_id (VARCHAR2 36 UUID).
 */
public interface CardMasterRepository extends JpaRepository<CardMaster, String> {

    /**
     * Find all cards for a customer
     */
    List<CardMaster> findByCustomerId(String customerId);

    /**
     * Find card by customer and card ID
     */
    Optional<CardMaster> findByCardIdAndCustomerId(String cardId, String customerId);

    /**
     * Find card by card token (for transaction validation)
     */
    Optional<CardMaster> findByCardToken(String cardToken);
}