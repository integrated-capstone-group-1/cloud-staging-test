package com.capstone.accounts.repository;

import com.capstone.accounts.entity.SystemParameterAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for SYSTEM_PARAMETER_AUDIT table (DIG-17).
 * Holds immutable history of all parameter updates.
 */
@Repository
public interface SystemParameterAuditRepository extends JpaRepository<SystemParameterAudit, String> {

    /**
     * Retrieve audit history for a specific parameter key, ordered from newest to oldest.
     */
    List<SystemParameterAudit> findByParamKeyOrderByChangedAtDesc(String paramKey);
}
