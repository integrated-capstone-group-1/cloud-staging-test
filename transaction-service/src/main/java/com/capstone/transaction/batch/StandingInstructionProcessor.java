package com.capstone.transaction.batch;

import com.capstone.transaction.dto.PaymentRequest;
import com.capstone.transaction.entity.oracle.StandingInstruction;
import com.capstone.transaction.service.PaymentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Processes individual standing instructions during the End-of-Day (EOD) batch run.
 * 
 * [LEARNER GUIDE / W-H-Y MODEL]:
 * - WHAT: Recurring payment executor with idempotency and isolation guards.
 * - HOW: Formulates a deterministic idempotency key (SI-{id}-{date}), calls PaymentOrchestrator,
 *        advances next_execution_date, and persists to Oracle repository. Catches failures per-item.
 * - WHY: Ensures exactly-once execution per scheduled day and prevents single-account payment failures
 *        from aborting the entire batch processing pipeline.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class StandingInstructionProcessor {

    private final PaymentOrchestrator paymentOrchestrator;
    private final com.capstone.transaction.repository.oracle.StandingInstructionRepository repository;

    /**
     * Executes a single standing instruction.
     * 
     * TRACE:
     * 1. Data Touched: Oracle standing_instruction, transaction_master, account_master (via Core CBS).
     * 2. Idempotency Guard: SI-{id}-{currentDate} prevents double-debiting on re-runs.
     * 3. Resilient Error Isolation: Catches exceptions so subsequent queued instructions continue.
     */
    @Transactional
    public void process(StandingInstruction instruction) {
        // Deterministic idempotency key: prevents duplicate charge if batch runs again on same day
        String idempotencyKey = "SI-" + instruction.getInstructionId() + "-" + LocalDate.now();
        PaymentRequest request = PaymentRequest.builder()
                .senderAccountId(instruction.getSourceAccountId())
                .recipientAccountId(instruction.getTargetAccountId())
                .amount(instruction.getAmount())
                .currency(instruction.getCurrencyCode())
                .build();
        
        try {
            paymentOrchestrator.processPayment(request, idempotencyKey, instruction.getCustomerId());
            advanceNextExecutionDate(instruction);
            repository.save(instruction);
        } catch (Exception e) {
            // Error Isolation: Log failure and continue batch without crashing or rolling back other items
            log.error("Failed to process standing instruction {}: {}", instruction.getInstructionId(), e.getMessage());
        }
    }

    /**
     * Advances the schedule to the next due period based on frequency (DAILY, WEEKLY, MONTHLY).
     * Ensures this instruction will not qualify again in subsequent EOD queries on the same date.
     */
    private void advanceNextExecutionDate(StandingInstruction instruction) {
        LocalDate nextDate = instruction.getNextExecutionDate();
        switch (instruction.getFrequency()) {
            case "DAILY": nextDate = nextDate.plusDays(1); break;
            case "WEEKLY": nextDate = nextDate.plusWeeks(1); break;
            case "MONTHLY": nextDate = nextDate.plusMonths(1); break;
        }
        instruction.setNextExecutionDate(nextDate);
    }
}
