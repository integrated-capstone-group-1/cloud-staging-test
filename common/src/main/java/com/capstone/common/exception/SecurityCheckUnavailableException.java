package com.capstone.common.exception;

public class SecurityCheckUnavailableException extends RuntimeException {

    public SecurityCheckUnavailableException(String message) {
        super(message);
    }

    public SecurityCheckUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
