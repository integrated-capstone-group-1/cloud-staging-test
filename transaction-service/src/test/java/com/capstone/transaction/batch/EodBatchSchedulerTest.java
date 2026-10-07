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

    @InjectMocks
    private EodBatchScheduler scheduler;

    @Test
    void runEodBatch_processesActiveInstructions() {
        StandingInstruction instr = StandingInstruction.builder()
                .instructionId("SI-1")
                .status("ACTIVE")
                .nextExecutionDate(LocalDate.now())
                .build();
        
        when(repository.findByStatusAndNextExecutionDateLessThanEqual("ACTIVE", LocalDate.now()))
                .thenReturn(List.of(instr));

        scheduler.runEodBatch();

        verify(processor).process(instr);
    }
}
