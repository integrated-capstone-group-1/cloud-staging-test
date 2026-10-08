package com.bank.reconciliation.repository.postgres;

import com.bank.reconciliation.entity.postgres.LedgerMutationAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface LedgerMutationAuditRepository extends JpaRepository<LedgerMutationAudit, UUID> {

    List<LedgerMutationAudit> findAllByTxnId(String txnId);

    List<LedgerMutationAudit> findAllByAccountId(String accountId);

    // No posted_at column on this table - created_at is the posting time,
    // since rows are immutable (append-only trigger) from insert onward.
    @Query("select m from LedgerMutationAudit m where m.createdAt >= :windowStart and m.createdAt < :windowEnd")
    List<LedgerMutationAudit> findAllInWindow(@Param("windowStart") OffsetDateTime windowStart,
                                               @Param("windowEnd") OffsetDateTime windowEnd);

    @Query("select coalesce(sum(case when m.mutationType = 'CREDIT' then m.mutationAmount else -m.mutationAmount end), 0) " +
           "from LedgerMutationAudit m where m.accountId = :accountId and m.auditState in ('COMMITTED', 'POSTED')")
    java.math.BigDecimal calculateExpectedBalanceForAccount(@Param("accountId") String accountId);
}
