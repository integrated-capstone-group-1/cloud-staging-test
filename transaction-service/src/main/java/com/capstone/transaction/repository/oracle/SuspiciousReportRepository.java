package com.capstone.transaction.repository.oracle;

import com.capstone.transaction.entity.oracle.SuspiciousReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Repository for SUSPICIOUS_REPORT in Oracle DB (DIG-91).
 */
public interface SuspiciousReportRepository extends JpaRepository<SuspiciousReport, String> {

    List<SuspiciousReport> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<SuspiciousReport> findByTxnIdOrderByCreatedAtDesc(String txnId);

    List<SuspiciousReport> findByStatusOrderByCreatedAtDesc(String status);
}

