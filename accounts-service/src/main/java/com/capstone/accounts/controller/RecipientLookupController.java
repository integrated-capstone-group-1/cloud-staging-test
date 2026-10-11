package com.capstone.accounts.controller;

import com.capstone.accounts.service.RecipientLookupService;
import com.capstone.common.dto.RecipientLookupResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RecipientLookupController {

    private final RecipientLookupService recipientLookupService;

    @GetMapping(value = {"/api/v1/recipients/lookup", "/api/recipients/lookup"})
    public ResponseEntity<RecipientLookupResponse> lookupRecipient(
            @RequestParam("account_id") String accountId) {
        RecipientLookupResponse response = recipientLookupService.lookupRecipient(accountId);
        return ResponseEntity.ok(response);
    }
}
