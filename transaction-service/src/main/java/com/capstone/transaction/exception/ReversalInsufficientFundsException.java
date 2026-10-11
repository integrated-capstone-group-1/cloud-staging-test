package com.capstone.transaction.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class ReversalInsufficientFundsException extends ResponseStatusException {
    public ReversalInsufficientFundsException(String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
