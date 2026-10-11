package com.capstone.forex.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public class FxRateStaleException extends RuntimeException {
    public FxRateStaleException(String message) {
        super(message);
    }
}
