package com.aguaviva.api.mapper;

import com.aguaviva.api.dto.response.OperacaoPainelResponseDto;
import com.aguaviva.service.OperacaoPainelService;
import java.util.Objects;

public final class OperacaoPainelMapper {

    private OperacaoPainelMapper() {}

    public static OperacaoPainelResponseDto toResponse(OperacaoPainelService.OperacaoPainelResultado source) {
        Objects.requireNonNull(source, "OperacaoPainelResultado nao pode ser nulo");

        return new OperacaoPainelResponseDto(
                source.atualizadoEm(),
                source.ambiente(),
                new OperacaoPainelResponseDto.PedidosPorStatusDto(
                        source.pedidosPorStatus().pendente(),
                        source.pedidosPorStatus().confirmado(),
                        source.pedidosPorStatus().emRota(),
                        source.pedidosPorStatus().entregue(),
                        source.pedidosPorStatus().cancelado()),
                new OperacaoPainelResponseDto.IndicadoresEntregaDto(
                        source.indicadoresEntrega().totalFinalizadas(),
                        source.indicadoresEntrega().entregasConcluidas(),
                        source.indicadoresEntrega().entregasCanceladas(),
                        source.indicadoresEntrega().taxaSucessoPercentual()),
                new OperacaoPainelResponseDto.RotasResumoDto(
                        source.rotas().emAndamento().stream()
                                .map(rota -> new OperacaoPainelResponseDto.RotaEmAndamentoDto(
                                        rota.rotaId(), rota.entregadorId(), rota.pendentes(), rota.emExecucao()))
                                .toList(),
                        source.rotas().planejadas().stream()
                                .map(rota -> new OperacaoPainelResponseDto.RotaPlanejadaDto(
                                        rota.rotaId(), rota.entregadorId(), rota.pendentes()))
                                .toList()),
                new OperacaoPainelResponseDto.FilasResumoDto(
                        source.filas().pendentesElegiveis().stream()
                                .map(item -> new OperacaoPainelResponseDto.PendenteElegivelDto(
                                        item.pedidoId(), item.criadoEm(), item.quantidadeGaloes(), item.janelaTipo()))
                                .toList(),
                        source.filas().confirmadosSecundaria().stream()
                                .map(item -> new OperacaoPainelResponseDto.ConfirmadoSecundariaDto(
                                        item.pedidoId(),
                                        item.rotaId(),
                                        item.ordemNaRota(),
                                        item.entregadorId(),
                                        item.quantidadeGaloes()))
                                .toList(),
                        source.filas().emRotaPrimaria().stream()
                                .map(item -> new OperacaoPainelResponseDto.EmRotaPrimariaDto(
                                        item.pedidoId(),
                                        item.rotaId(),
                                        item.entregaId(),
                                        item.entregadorId(),
                                        item.quantidadeGaloes(),
                                        item.statusEntrega()))
                                .toList()));
    }
}
