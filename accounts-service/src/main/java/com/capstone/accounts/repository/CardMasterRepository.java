package com.capstone.accounts.repository;

import com.capstone.accounts.entity.CardMaster;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CardMasterRepository extends JpaRepository<CardMaster, String> {

    List<CardMaster> findByCustomerId(String customerId);

    List<CardMaster> findByAccountId(String accountId);

    Optional<CardMaster> findByCardToken(String cardToken);
}

