package com.aguaviva.service;

public enum ReplanejamentoTriggerKind {
    NONE,
    PRIMARIO,
    SECUNDARIO;

    public boolean replaneja() {
        return this != NONE;
    }
}
