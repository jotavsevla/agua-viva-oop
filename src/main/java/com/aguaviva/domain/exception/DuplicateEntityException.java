package com.aguaviva.domain.exception;

public class DuplicateEntityException extends AguaVivaException {

    private static final long serialVersionUID = 1L;

    public DuplicateEntityException(String entityType, String field, Object value) {
        super(entityType + " ja existe com " + field + "=" + value);
    }

    public DuplicateEntityException(String message) {
        super(message);
    }
}
