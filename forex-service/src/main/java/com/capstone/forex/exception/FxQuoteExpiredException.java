package com.capstone.forex.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class FxQuoteExpiredException extends RuntimeException {
    public FxQuoteExpiredException(String message) {
        super(message);
    }
}
