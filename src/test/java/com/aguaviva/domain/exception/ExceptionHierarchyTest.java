package com.aguaviva.domain.exception;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.SQLException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ExceptionHierarchyTest {

    @Test
    void aguaVivaExceptionDevePreservarMensagemECausa() {
        IllegalStateException cause = new IllegalStateException("root");

        AguaVivaException ex = new AguaVivaException("erro", cause);

        assertEquals("erro", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertInstanceOf(RuntimeException.class, ex);
    }

    @Test
    void entityNotFoundExceptionDeveFormatarMensagemComEntidadeEId() {
        EntityNotFoundException ex = new EntityNotFoundException("Pedido", 10);

        assertEquals("Pedido nao encontrado: 10", ex.getMessage());
        assertInstanceOf(AguaVivaException.class, ex);
    }

    @Test
    void duplicateEntityExceptionDeveFormatarMensagemComCampoEValor() {
        DuplicateEntityException ex = new DuplicateEntityException("Cliente", "telefone", "11999990000");

        assertEquals("Cliente ja existe com telefone=11999990000", ex.getMessage());
        assertInstanceOf(AguaVivaException.class, ex);
    }

    @Test
    void businessRuleExceptionDeveAceitarMensagemECausa() {
        UnsupportedOperationException cause = new UnsupportedOperationException("x");

        BusinessRuleException ex = new BusinessRuleException("regra violada", cause);

        assertEquals("regra violada", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertInstanceOf(AguaVivaException.class, ex);
    }

    @Test
    void databaseExceptionDeveAceitarMensagemECausa() {
        SQLException cause = new SQLException("db down");

        DatabaseException ex = new DatabaseException("falha sql", cause);

        assertEquals("falha sql", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertInstanceOf(AguaVivaException.class, ex);
    }

    @Test
    void databaseExceptionComApenasCausaDeveDefinirMensagemPadrao() {
        SQLException cause = new SQLException("timeout");

        DatabaseException ex = new DatabaseException(cause);

        assertEquals("Database operation failed", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertInstanceOf(AguaVivaException.class, ex);
    }
}
