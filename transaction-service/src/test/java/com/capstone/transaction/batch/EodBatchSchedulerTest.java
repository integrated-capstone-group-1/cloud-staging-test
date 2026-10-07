package com.capstone.transaction.batch;

import com.capstone.transaction.entity.oracle.StandingInstruction;
import com.capstone.transaction.repository.oracle.StandingInstructionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EodBatchSchedulerTest {

    @Mock
    private StandingInstructionRepository repository;

    @Mock
    private StandingInstructionProcessor processor;

    @Mock
    private com.capstone.transaction.kafka.TransactionEventProducer eventProducer;

    @InjectMocks
    private EodBatchScheduler scheduler;

    @Test
    void runEodBatch_processesActiveInstructionsAndPublishesEvent() {
        StandingInstruction instr = StandingInstruction.builder()
                .instructionId("SI-1")
                .status("ACTIVE")
                .nextExecutionDate(LocalDate.now())
                .build();
        
        when(repository.findByStatusAndNextExecutionDateLessThanEqual("ACTIVE", LocalDate.now()))
                .thenReturn(List.of(instr));

        scheduler.runEodBatch();

        verify(processor, times(1)).process(instr);
        verify(eventProducer, times(1)).publishRaw(eq("batch-eod-" + LocalDate.now()), any());
    }

    @Test
    void dueStandingInstruction_triggersPaymentAndAdvancesNextExecutionDate() {
        com.capstone.transaction.service.PaymentOrchestrator paymentOrchestrator = mock(com.capstone.transaction.service.PaymentOrchestrator.class);
        StandingInstructionRepository siRepo = mock(StandingInstructionRepository.class);
        StandingInstructionProcessor realProcessor = new StandingInstructionProcessor(paymentOrchestrator, siRepo);

        LocalDate today = LocalDate.now();
        StandingInstruction instr = StandingInstruction.builder()
                .instructionId("SI-100")
                .customerId("cust-01")
                .sourceAccountId("acct-src-01")
                .targetAccountId("acct-tgt-01")
                .amount(new BigDecimal("150.0000"))
                .currencyCode("PHP")
                .frequency("DAILY")
                .status("ACTIVE")
                .nextExecutionDate(today)
                .build();

        realProcessor.process(instr);

        verify(paymentOrchestrator, times(1)).processPayment(
                argThat(req -> req.getSenderAccountId().equals("acct-src-01")
                        && req.getRecipientAccountId().equals("acct-tgt-01")
                        && req.getAmount().equals(new BigDecimal("150.0000"))
                        && req.getCurrency().equals("PHP")),
                eq("SI-SI-100-" + today),
                eq("cust-01")
        );
        org.junit.jupiter.api.Assertions.assertEquals(today.plusDays(1), instr.getNextExecutionDate());
        verify(siRepo, times(1)).save(instr);
    }

    @Test
    void rerunBatchOnSameDay_doesNotDuplicateTransfers() {
        LocalDate today = LocalDate.now();
        StandingInstruction instr = StandingInstruction.builder()
                .instructionId("SI-200")
                .status("ACTIVE")
                .nextExecutionDate(today)
                .build();

        // First run returns the due instruction
        when(repository.findByStatusAndNextExecutionDateLessThanEqual("ACTIVE", today))
                .thenReturn(List.of(instr))
                .thenReturn(List.of()); // Second run on same day returns empty since next_execution_date was advanced

        scheduler.runEodBatch();
        verify(processor, times(1)).process(instr);

        // Re-running on same day
        scheduler.runEodBatch();
        verify(processor, times(1)).process(instr); // Still only 1 invocation total
    }

    @Test
    void insufficientBalanceInstruction_logsErrorWithoutAbortingRemainingBatchQueue() {
        com.capstone.transaction.service.PaymentOrchestrator paymentOrchestrator = mock(com.capstone.transaction.service.PaymentOrchestrator.class);
        StandingInstructionRepository siRepo = mock(StandingInstructionRepository.class);
        StandingInstructionProcessor realProcessor = new StandingInstructionProcessor(paymentOrchestrator, siRepo);

        LocalDate today = LocalDate.now();
        StandingInstruction failingInstr = StandingInstruction.builder()
                .instructionId("SI-FAIL")
                .customerId("cust-01")
                .sourceAccountId("acct-poor-01")
                .targetAccountId("acct-tgt-01")
                .amount(new BigDecimal("999999.0000"))
                .currencyCode("PHP")
                .frequency("MONTHLY")
                .status("ACTIVE")
                .nextExecutionDate(today)
                .build();

        StandingInstruction successfulInstr = StandingInstruction.builder()
                .instructionId("SI-SUCCESS")
                .customerId("cust-02")
                .sourceAccountId("acct-rich-01")
                .targetAccountId("acct-tgt-02")
                .amount(new BigDecimal("50.0000"))
                .currencyCode("PHP")
                .frequency("DAILY")
                .status("ACTIVE")
                .nextExecutionDate(today)
                .build();

        doThrow(new RuntimeException("INSUFFICIENT_FUNDS"))
                .when(paymentOrchestrator).processPayment(any(), eq("SI-SI-FAIL-" + today), eq("cust-01"));

        // Failing instruction does not throw to caller
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> realProcessor.process(failingInstr));

        // Successful instruction processes normally
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> realProcessor.process(successfulInstr));

        verify(paymentOrchestrator, times(1)).processPayment(any(), eq("SI-SI-SUCCESS-" + today), eq("cust-02"));
        org.junit.jupiter.api.Assertions.assertEquals(today.plusDays(1), successfulInstr.getNextExecutionDate());
        verify(siRepo, times(1)).save(successfulInstr);
    }
}
