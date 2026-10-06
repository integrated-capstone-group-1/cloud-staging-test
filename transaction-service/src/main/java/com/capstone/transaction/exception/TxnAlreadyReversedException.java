package com.capstone.transaction.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class TxnAlreadyReversedException extends ResponseStatusException {
    public TxnAlreadyReversedException(String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, reason);
    }
}
