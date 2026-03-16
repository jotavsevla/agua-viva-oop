package com.aguaviva.domain.exception;

public class AguaVivaException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AguaVivaException(String message) {
        super(message);
    }

    public AguaVivaException(String message, Throwable cause) {
        super(message, cause);
    }
}
