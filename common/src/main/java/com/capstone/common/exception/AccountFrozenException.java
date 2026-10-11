package com.capstone.common.exception;

public class AccountFrozenException extends IllegalStateException {

    public AccountFrozenException(String message) {
        super(message);
    }
}

