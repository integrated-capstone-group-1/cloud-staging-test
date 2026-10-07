package com.capstone.transaction.batch;

import com.capstone.transaction.dto.PaymentRequest;
import com.capstone.transaction.entity.oracle.StandingInstruction;
import com.capstone.transaction.service.PaymentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

@Service
@RequiredArgsConstructor
@Slf4j
public class StandingInstructionProcessor {

    private final PaymentOrchestrator paymentOrchestrator;
    private final com.capstone.transaction.repository.oracle.StandingInstructionRepository repository;

    @Transactional
    public void process(StandingInstruction instruction) {
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
            log.error("Failed to process standing instruction {}: {}", instruction.getInstructionId(), e.getMessage());
        }
    }

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
