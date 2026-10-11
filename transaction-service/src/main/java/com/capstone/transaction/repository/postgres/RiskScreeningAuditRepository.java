package com.capstone.transaction.repository.postgres;

import com.capstone.transaction.entity.postgres.RiskScreeningAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RiskScreeningAuditRepository extends JpaRepository<RiskScreeningAudit, UUID> {

    List<RiskScreeningAudit> findByTxnId(String txnId);

    List<RiskScreeningAudit> findByCustomerId(String customerId);
}

