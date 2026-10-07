package com.capstone.transaction.repository.oracle;

import com.capstone.transaction.entity.oracle.AdminApproval;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for ADMIN_APPROVAL table via Oracle primary DataSource.
 */
public interface AdminApprovalRepository extends JpaRepository<AdminApproval, String> {

    List<AdminApproval> findByStatus(String status);

    List<AdminApproval> findByTargetTypeAndTargetId(String targetType, String targetId);

    Optional<AdminApproval> findByTargetTypeAndTargetIdAndStatus(String targetType, String targetId, String status);
}
