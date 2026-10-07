package com.capstone.transaction.batch;

import com.capstone.transaction.entity.oracle.StandingInstruction;
import com.capstone.transaction.repository.oracle.StandingInstructionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EodBatchScheduler {

    private final StandingInstructionRepository repository;
    private final StandingInstructionProcessor processor;
    private final com.capstone.transaction.kafka.TransactionEventProducer eventProducer;

    @Scheduled(cron = "0 0 0 * * *")
    public void runEodBatch() {
        log.info("Starting EOD batch processor for standing instructions");
        List<StandingInstruction> instructions = repository
                .findByStatusAndNextExecutionDateLessThanEqual("ACTIVE", LocalDate.now());
        
        for (StandingInstruction instruction : instructions) {
            processor.process(instruction);
        }

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
