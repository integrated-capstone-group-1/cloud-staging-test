package com.capstone.transaction.batch;

import com.capstone.transaction.entity.oracle.StandingInstruction;
import com.capstone.transaction.repository.oracle.StandingInstructionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * End-of-Day (EOD) Batch Scheduler.
 * 
 * [LEARNER GUIDE / W-H-Y MODEL]:
 * - WHAT: Automated nightly job orchestrating recurring standing instructions and EOD completion events.
 * - HOW: Runs nightly via cron or on-demand via Admin API, queries due active instructions,
 *        delegates each to StandingInstructionProcessor, and publishes 'batch.eod.completed' to Kafka.
 * - WHY: Automates daily retail banking settlement commitments while decoupling downstream audit/reporting
 *        systems via event-driven architecture.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EodBatchScheduler {

    private final StandingInstructionRepository repository;
    private final StandingInstructionProcessor processor;
    private final com.capstone.transaction.kafka.TransactionEventProducer eventProducer;

    /**
     * Executes the EOD batch flow:
     * 1. TRACE - Query: Finds all active standing instructions with next_execution_date <= CURRENT_DATE.
     * 2. TRACE - Process: Iterates and executes each instruction via StandingInstructionProcessor.
     * 3. TRACE - Publish: Emits 'batch.eod.completed' event to Kafka topic 'transaction.events'.
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void runEodBatch() {
        log.info("Starting EOD batch processor for standing instructions");
        List<StandingInstruction> instructions = repository
                .findByStatusAndNextExecutionDateLessThanEqual("ACTIVE", LocalDate.now());
        
        for (StandingInstruction instruction : instructions) {
            processor.process(instruction);
        }

        // Kafka Event Publication: Signals downstream audit and reconciliation services that EOD finished
        if (eventProducer != null) {
            java.util.Map<String, Object> eventPayload = java.util.Map.of(
                    "eventType", "batch.eod.completed",
                    "executionDate", LocalDate.now().toString(),
                    "instructionsProcessed", instructions.size(),
                    "completedAt", java.time.Instant.now().toString()
            );
            eventProducer.publishRaw("batch-eod-" + LocalDate.now(), eventPayload);
        }

        log.info("EOD batch finished");
    }
}
