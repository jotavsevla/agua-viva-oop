package com.aguaviva.domain.exception;

public class DatabaseException extends AguaVivaException {

    private static final long serialVersionUID = 1L;

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public DatabaseException(Throwable cause) {
        super("Database operation failed", cause);
    }
}
