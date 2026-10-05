package com.capstone.accounts.service;

import com.capstone.accounts.entity.AccountMaster;
import com.capstone.accounts.entity.PostingReference;
import com.capstone.accounts.repository.*;
import com.capstone.common.dto.CoreSettlementRequest;
import com.capstone.common.dto.CoreSettlementResponse;
import com.capstone.common.dto.SettlementLegDto;
import com.capstone.common.exception.InsufficientBalanceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CoreSettlementServiceTest {

    @Mock
    private AccountRepository accountRepository;
    @Mock
    private AccountHoldRepository accountHoldRepository;
    @Mock
    private PostingReferenceRepository postingReferenceRepository;
    @Mock
    private OutboxMasterRepository outboxMasterRepository;
    @Mock
    private BalanceCacheService balanceCacheService;

    @InjectMocks
    private CoreSettlementService coreSettlementService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        var field = CoreSettlementService.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(coreSettlementService, objectMapper);
    }

    @Test
    void settle_shouldExecuteMultiLegSettlementSuccessfully() {
        String postingRefId = "POST-001";
        
        AccountMaster debitAccount = AccountMaster.builder()
                .accountId("ACC-001")
                .balanceAmount(new BigDecimal("1000.00"))
                .heldAmount(BigDecimal.ZERO)
                .currencyCode("USD")
                .accountStatus("ACTIVE")
                .build();

        AccountMaster creditAccount = AccountMaster.builder()
                .accountId("ACC-002")
                .balanceAmount(new BigDecimal("500.00"))
                .heldAmount(BigDecimal.ZERO)
                .currencyCode("USD")
                .accountStatus("ACTIVE")
                .build();

        List<SettlementLegDto> legs = List.of(
                new SettlementLegDto("ACC-001", "DEBIT", new BigDecimal("100.00"), "USD", 1),
                new SettlementLegDto("ACC-002", "CREDIT", new BigDecimal("100.00"), "USD", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(postingRefId, "TRANSFER", null, legs);

        when(postingReferenceRepository.findByPostingReferenceId(postingRefId))
                .thenReturn(Optional.empty());
        when(accountRepository.findAllByIdsForUpdate(any()))
                .thenReturn(List.of(debitAccount, creditAccount));
        when(postingReferenceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(outboxMasterRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CoreSettlementResponse response = coreSettlementService.settle(request);

        assertNotNull(response);
        assertEquals("SETTLED", response.status());
        assertEquals(new BigDecimal("900.00"), debitAccount.getBalanceAmount());
        assertEquals(new BigDecimal("600.00"), creditAccount.getBalanceAmount());
        
        verify(balanceCacheService).put(eq("ACC-001"), any());
        verify(balanceCacheService).put(eq("ACC-002"), any());
    }

    @Test
    void settle_shouldRejectInsufficientBalance() {
        String postingRefId = "POST-002";
        
        AccountMaster debitAccount = AccountMaster.builder()
                .accountId("ACC-003")
                .balanceAmount(new BigDecimal("50.00"))
                .heldAmount(BigDecimal.ZERO)
                .currencyCode("USD")
                .accountStatus("ACTIVE")
                .build();

        AccountMaster creditAccount = AccountMaster.builder()
                .accountId("ACC-004")
                .balanceAmount(new BigDecimal("500.00"))
                .heldAmount(BigDecimal.ZERO)
                .currencyCode("USD")
                .accountStatus("ACTIVE")
                .build();

        List<SettlementLegDto> legs = List.of(
                new SettlementLegDto("ACC-003", "DEBIT", new BigDecimal("100.00"), "USD", 1),
                new SettlementLegDto("ACC-004", "CREDIT", new BigDecimal("100.00"), "USD", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(postingRefId, "TRANSFER", null, legs);

        when(postingReferenceRepository.findByPostingReferenceId(postingRefId))
                .thenReturn(Optional.empty());
        when(accountRepository.findAllByIdsForUpdate(any()))
                .thenReturn(List.of(debitAccount, creditAccount));

        assertThrows(InsufficientBalanceException.class, 
                () -> coreSettlementService.settle(request));
    }

    @Test
    void settle_shouldRespectHeldAmountForAvailableBalance() {
        String postingRefId = "POST-003";
        
        AccountMaster debitAccount = AccountMaster.builder()
                .accountId("ACC-005")
                .balanceAmount(new BigDecimal("500.00"))
                .heldAmount(new BigDecimal("400.00"))
                .currencyCode("USD")
                .accountStatus("ACTIVE")
                .build();

        AccountMaster creditAccount = AccountMaster.builder()
                .accountId("ACC-006")
                .balanceAmount(new BigDecimal("500.00"))
                .heldAmount(BigDecimal.ZERO)
                .currencyCode("USD")
                .accountStatus("ACTIVE")
                .build();

        List<SettlementLegDto> legs = List.of(
                new SettlementLegDto("ACC-005", "DEBIT", new BigDecimal("150.00"), "USD", 1),
                new SettlementLegDto("ACC-006", "CREDIT", new BigDecimal("150.00"), "USD", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(postingRefId, "TRANSFER", null, legs);

        when(postingReferenceRepository.findByPostingReferenceId(postingRefId))
                .thenReturn(Optional.empty());
        when(accountRepository.findAllByIdsForUpdate(any()))
                .thenReturn(List.of(debitAccount, creditAccount));

        assertThrows(InsufficientBalanceException.class,
                () -> coreSettlementService.settle(request));
    }

    @Test
    void settle_shouldRejectUnbalancedSettlement() {
        String postingRefId = "POST-004";
        
        List<SettlementLegDto> legs = List.of(
                new SettlementLegDto("ACC-007", "DEBIT", new BigDecimal("100.00"), "USD", 1),
                new SettlementLegDto("ACC-008", "CREDIT", new BigDecimal("50.00"), "USD", 2)
        );

        CoreSettlementRequest request = new CoreSettlementRequest(postingRefId, "TRANSFER", null, legs);

        when(postingReferenceRepository.findByPostingReferenceId(postingRefId))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> coreSettlementService.settle(request));
    }

    @Test
    void getSettlementStatus_shouldReturnExistingStatus() {
        String postingRefId = "POST-006";
        PostingReference ref = new PostingReference();
        ref.setPostingReferenceId(postingRefId);
        ref.setCoreReferenceId("CORE-456");
        ref.setStatus("SETTLED");
        ref.setSettledAt(LocalDateTime.now());

        when(postingReferenceRepository.findByPostingReferenceId(postingRefId))
                .thenReturn(Optional.of(ref));

        CoreSettlementResponse response = coreSettlementService.getSettlementStatus(postingRefId);

        assertEquals("SETTLED", response.status());
        assertEquals(postingRefId, response.postingReferenceId());
    }
}