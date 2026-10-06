package com.capstone.common.exception;

public class InvalidCardOperationException extends RuntimeException {
    public InvalidCardOperationException(String message) {
        super(message);
    }
}