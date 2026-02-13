package com.aguaviva.domain.pedido;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PedidoStateMachineTest {

    @Test
    void devePermitirTransicaoPendenteParaConfirmado() {
        PedidoTransitionResult resultado = PedidoStateMachine.transicionar(
                PedidoStatus.PENDENTE,
                PedidoStatus.CONFIRMADO
        );

        assertEquals(PedidoStatus.CONFIRMADO, resultado.novoStatus());
        assertFalse(resultado.geraCobrancaCancelamento());
    }

    @Test
    void devePermitirTransicaoConfirmadoParaEmRota() {
        PedidoTransitionResult resultado = PedidoStateMachine.transicionar(
                PedidoStatus.CONFIRMADO,
                PedidoStatus.EM_ROTA
        );

        assertEquals(PedidoStatus.EM_ROTA, resultado.novoStatus());
    }

    @Test
    void devePermitirTransicaoEmRotaParaEntregue() {
        PedidoTransitionResult resultado = PedidoStateMachine.transicionar(
                PedidoStatus.EM_ROTA,
                PedidoStatus.ENTREGUE
        );

        assertEquals(PedidoStatus.ENTREGUE, resultado.novoStatus());
        assertFalse(resultado.geraCobrancaCancelamento());
    }

    @Test
    void devePermitirCancelamentoAntesDaRotaSemGerarCobranca() {
        PedidoTransitionResult resultado = PedidoStateMachine.transicionar(
                PedidoStatus.PENDENTE,
                PedidoStatus.CANCELADO
        );

        assertEquals(PedidoStatus.CANCELADO, resultado.novoStatus());
        assertFalse(resultado.geraCobrancaCancelamento());
    }

    @Test
    void devePermitirCancelamentoEmRotaGerandoCobranca() {
        PedidoTransitionResult resultado = PedidoStateMachine.transicionar(
                PedidoStatus.EM_ROTA,
                PedidoStatus.CANCELADO
        );

        assertEquals(PedidoStatus.CANCELADO, resultado.novoStatus());
        assertTrue(resultado.geraCobrancaCancelamento());
    }

    @Test
    void deveRejeitarTransicaoInvalidaEntregueParaPendente() {
        assertThrows(IllegalStateException.class, () ->
                PedidoStateMachine.transicionar(PedidoStatus.ENTREGUE, PedidoStatus.PENDENTE)
        );
    }

    @Test
    void deveRejeitarStatusAtualNulo() {
        assertThrows(NullPointerException.class, () ->
                PedidoStateMachine.transicionar(null, PedidoStatus.PENDENTE)
        );
    }

    @Test
    void deveRejeitarStatusDestinoNulo() {
        assertThrows(NullPointerException.class, () ->
                PedidoStateMachine.transicionar(PedidoStatus.PENDENTE, null)
        );
    }
}
