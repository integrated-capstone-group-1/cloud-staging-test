package com.capstone.transaction.batch;

import com.capstone.common.dto.TransactionRequest;
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

    @Transactional
    public void process(StandingInstruction instruction) {
        String idempotencyKey = "SI-" + instruction.getInstructionId() + "-" + LocalDate.now();
        TransactionRequest request = TransactionRequest.builder()
                .debitAccountId(instruction.getSourceAccountId())
                .creditAccountId(instruction.getTargetAccountId())
                .amount(instruction.getAmount())
                .currencyCode(instruction.getCurrencyCode())
                .isCrossCurrency(false) // Assuming same currency based on simple design
                .build();
        
        try {
            paymentOrchestrator.processPayment(request, idempotencyKey, instruction.getCustomerId());
            advanceNextExecutionDate(instruction);
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
