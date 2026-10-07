package com.capstone.transaction.repository.oracle;

import com.capstone.transaction.entity.oracle.StandingInstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StandingInstructionRepository extends JpaRepository<StandingInstruction, String> {
    List<StandingInstruction> findByStatusAndNextExecutionDateLessThanEqual(String status, LocalDate date);
}
