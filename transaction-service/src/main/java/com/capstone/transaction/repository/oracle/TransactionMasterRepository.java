
package com.capstone.transaction.repository.oracle;

import com.capstone.transaction.entity.oracle.TransactionMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Targets TRANSACTION_MASTER via the Oracle primary DataSource.
 * String PK: txn_id (VARCHAR2 36 UUID).
 */
public interface TransactionMasterRepository extends JpaRepository<TransactionMaster, String>, JpaSpecificationExecutor<TransactionMaster> {

    List<TransactionMaster> findByTxnStatusAndInitiatedAtBefore(String txnStatus, LocalDateTime initiatedAt);

    Optional<TransactionMaster> findByReversalOfTxnId(String reversalOfTxnId);

    Optional<TransactionMaster> findByCustomerIdAndIdempotencyKey(String customerId, String idempotencyKey);

    List<TransactionMaster> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    @Modifying
    @Query("UPDATE TransactionMaster t SET t.txnStatus = :newStatus, t.stage = :newStage, t.updatedAt = :now WHERE t.txnId = :txnId AND t.txnStatus = :expectedStatus")
    int updateStatusCas(@Param("txnId") String txnId,
                        @Param("expectedStatus") String expectedStatus,
                        @Param("newStatus") String newStatus,
                        @Param("newStage") String newStage,
                        @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE TransactionMaster t SET t.txnStatus = 'CANCELLED', t.stage = 'CANCELLED', t.cancelReason = :cancelReason, t.updatedAt = :now WHERE t.txnId = :txnId AND t.txnStatus IN ('INITIATED', 'AUTHORIZED')")
    int cancelTransactionCas(@Param("txnId") String txnId,
                             @Param("cancelReason") String cancelReason,
                             @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE TransactionMaster t SET t.txnStatus = :newStatus, t.stage = :newStage, t.coreReferenceId = :coreReferenceId, t.completedAt = :now, t.updatedAt = :now WHERE t.txnId = :txnId")
    int markCompleted(@Param("txnId") String txnId,
                      @Param("newStatus") String newStatus,
                      @Param("newStage") String newStage,
                      @Param("coreReferenceId") String coreReferenceId,
                      @Param("now") LocalDateTime now);
}

