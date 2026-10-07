package com.capstone.accounts.controller;

import com.capstone.accounts.service.ParameterService;
import com.capstone.common.dto.ApiResponse;
import com.capstone.common.dto.SystemParameterDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/parameters")
@RequiredArgsConstructor
public class AdminParameterController {
    private final ParameterService parameterService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<SystemParameterDTO>>> getAllParameters() {
        return ResponseEntity.ok(ApiResponse.ok(parameterService.getAllParameters()));
    }

    @PutMapping("/{key}")
    public ResponseEntity<ApiResponse<SystemParameterDTO>> updateParameter(
            @PathVariable("key") String key,
            @RequestBody SystemParameterDTO update,
            @RequestParam(name = "reason", defaultValue = "Admin updated parameter") String reason,
            @RequestParam(name = "adminUser", defaultValue = "admin@ledgerbank.com") String adminUser) {
        SystemParameterDTO result = parameterService.updateParameter(key, update, adminUser, reason);
        return ResponseEntity.ok(ApiResponse.ok("Parameter updated", result));
    }
}
