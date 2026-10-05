package com.capstone.accounts.repository;

import com.capstone.accounts.entity.AccountHold;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountHoldRepository extends JpaRepository<AccountHold, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")})
    @Query("SELECT h FROM AccountHold h WHERE h.holdId = :holdId")
    Optional<AccountHold> findByIdForUpdate(@Param("holdId") String holdId);

    List<AccountHold> findByAccountIdAndStatus(String accountId, String status);

    Optional<AccountHold> findByHoldIdAndAccountId(String holdId, String accountId);

    List<AccountHold> findByStatusAndExpiresAtBefore(String status, LocalDateTime expiryTime);
}
