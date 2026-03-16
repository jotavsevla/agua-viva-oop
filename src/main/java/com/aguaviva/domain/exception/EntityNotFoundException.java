package com.aguaviva.domain.exception;

public class EntityNotFoundException extends AguaVivaException {

    private static final long serialVersionUID = 1L;

    public EntityNotFoundException(String entityType, Object id) {
        super(entityType + " nao encontrado: " + id);
    }

    public EntityNotFoundException(String message) {
        super(message);
    }
}
