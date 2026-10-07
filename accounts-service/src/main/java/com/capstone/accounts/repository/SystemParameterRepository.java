package com.capstone.accounts.repository;

import com.capstone.accounts.entity.SystemParameter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JPA repository for SYSTEM_PARAMETER table (DIG-17).
 * Owned exclusively by accounts-service.
 */
@Repository
public interface SystemParameterRepository extends JpaRepository<SystemParameter, String> {

    /**
     * Retrieve all active (is_active = 'Y') parameters.
     */
    List<SystemParameter> findByIsActive(String isActive);
}
