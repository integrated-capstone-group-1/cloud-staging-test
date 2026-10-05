package com.capstone.transaction.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class StateConflictException extends ResponseStatusException {

    public StateConflictException(String reason) {
        super(HttpStatus.CONFLICT, reason);
    }
}
