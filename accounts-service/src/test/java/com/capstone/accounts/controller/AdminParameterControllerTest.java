package com.capstone.accounts.controller;

import com.capstone.accounts.service.ParameterService;
import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.SystemParameterDTO;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminParameterControllerTest {

    @Mock
    private ParameterService parameterService;

    @InjectMocks
    private AdminParameterController adminParameterController;

    private final SystemParameterDTO sampleParam = new SystemParameterDTO(
            "SINGLE_TRANSFER_LIMIT",
            "Maximum single transfer amount",
            new BigDecimal("100000.00"),
            null,
            1
    );

    @Test
    @DisplayName("getAllParameters should return list of active parameters")
    void getAllParameters() {
        when(parameterService.getAllParameters()).thenReturn(List.of(sampleParam));

        var response = adminParameterController.getAllParameters();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().data()).hasSize(1);
        verify(parameterService).getAllParameters();
    }

    @Test
    @DisplayName("updateParameter should invoke service and return updated parameter")
    void updateParameter() {
        SystemParameterDTO updatedParam = new SystemParameterDTO(
                "SINGLE_TRANSFER_LIMIT",
                "Maximum single transfer amount",
                new BigDecimal("150000.00"),
                null,
                2
        );
        when(parameterService.updateParameter("SINGLE_TRANSFER_LIMIT", updatedParam, "admin@ledgerbank.com", "Test update"))
                .thenReturn(updatedParam);

        var response = adminParameterController.updateParameter(
                "SINGLE_TRANSFER_LIMIT", updatedParam, "Test update", "admin@ledgerbank.com");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().data().paramVersion()).isEqualTo(2);
        assertThat(response.getBody().data().paramValueNumeric()).isEqualByComparingTo(new BigDecimal("150000.00"));
        verify(parameterService).updateParameter("SINGLE_TRANSFER_LIMIT", updatedParam, "admin@ledgerbank.com", "Test update");
    }
}
