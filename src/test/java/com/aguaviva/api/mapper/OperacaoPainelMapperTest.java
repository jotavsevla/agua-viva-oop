package com.aguaviva.api.mapper;

import static org.junit.jupiter.api.Assertions.*;

import com.aguaviva.api.dto.response.OperacaoPainelResponseDto;
import com.aguaviva.service.OperacaoPainelService;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class OperacaoPainelMapperTest {

    @Test
    void toResponseDeveMapearEstruturasAninhadas() {
        OperacaoPainelService.OperacaoPainelResultado source = new OperacaoPainelService.OperacaoPainelResultado(
                "2026-03-16T10:00:00",
                "test",
                new OperacaoPainelService.PedidosPorStatus(1, 2, 3, 4, 5),
                new OperacaoPainelService.IndicadoresEntrega(10, 9, 1, 90.0),
                new OperacaoPainelService.RotasResumo(
                        List.of(new OperacaoPainelService.RotaEmAndamentoResumo(100, 7, 3, 1)),
                        List.of(new OperacaoPainelService.RotaPlanejadaResumo(101, 8, 2))),
                new OperacaoPainelService.FilasResumo(
                        List.of(new OperacaoPainelService.PendenteElegivelResumo(1, "2026-03-16T09:00:00", 2, "ASAP")),
                        List.of(new OperacaoPainelService.ConfirmadoSecundariaResumo(2, 100, 1, 7, 1)),
                        List.of(new OperacaoPainelService.EmRotaPrimariaResumo(3, 100, 999, 7, 1, "EM_EXECUCAO"))));

        OperacaoPainelResponseDto response = OperacaoPainelMapper.toResponse(source);

        assertEquals("test", response.ambiente());
        assertEquals(1, response.pedidosPorStatus().pendente());
        assertEquals(1, response.rotas().emAndamento().size());
        assertEquals(1, response.filas().pendentesElegiveis().size());
    }

    @Test
    void toResponseDeveLancarParaSourceNulo() {
        assertThrows(NullPointerException.class, () -> OperacaoPainelMapper.toResponse(null));
    }
}
