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
                toPedidosPorStatusDto(source.pedidosPorStatus()),
                toIndicadoresEntregaDto(source.indicadoresEntrega()),
                toRotasResumoDto(source.rotas()),
                toFilasResumoDto(source.filas()));
    }

    private static OperacaoPainelResponseDto.PedidosPorStatusDto toPedidosPorStatusDto(
            OperacaoPainelService.PedidosPorStatus source) {
        return new OperacaoPainelResponseDto.PedidosPorStatusDto(
                source.pendente(),
                source.confirmado(),
                source.emRota(),
                source.entregue(),
                source.cancelado());
    }

    private static OperacaoPainelResponseDto.IndicadoresEntregaDto toIndicadoresEntregaDto(
            OperacaoPainelService.IndicadoresEntrega source) {
        return new OperacaoPainelResponseDto.IndicadoresEntregaDto(
                source.totalFinalizadas(),
                source.entregasConcluidas(),
                source.entregasCanceladas(),
                source.taxaSucessoPercentual());
    }

    private static OperacaoPainelResponseDto.RotasResumoDto toRotasResumoDto(OperacaoPainelService.RotasResumo source) {
        return new OperacaoPainelResponseDto.RotasResumoDto(
                source.emAndamento().stream().map(OperacaoPainelMapper::toRotaEmAndamentoDto).toList(),
                source.planejadas().stream().map(OperacaoPainelMapper::toRotaPlanejadaDto).toList());
    }

    private static OperacaoPainelResponseDto.RotaEmAndamentoDto toRotaEmAndamentoDto(
            OperacaoPainelService.RotaEmAndamentoResumo rota) {
        return new OperacaoPainelResponseDto.RotaEmAndamentoDto(
                rota.rotaId(), rota.entregadorId(), rota.pendentes(), rota.emExecucao());
    }

    private static OperacaoPainelResponseDto.RotaPlanejadaDto toRotaPlanejadaDto(
            OperacaoPainelService.RotaPlanejadaResumo rota) {
        return new OperacaoPainelResponseDto.RotaPlanejadaDto(rota.rotaId(), rota.entregadorId(), rota.pendentes());
    }

    private static OperacaoPainelResponseDto.FilasResumoDto toFilasResumoDto(OperacaoPainelService.FilasResumo source) {
        return new OperacaoPainelResponseDto.FilasResumoDto(
                source.pendentesElegiveis().stream().map(OperacaoPainelMapper::toPendenteElegivelDto).toList(),
                source.confirmadosSecundaria().stream()
                        .map(OperacaoPainelMapper::toConfirmadoSecundariaDto)
                        .toList(),
                source.emRotaPrimaria().stream().map(OperacaoPainelMapper::toEmRotaPrimariaDto).toList());
    }

    private static OperacaoPainelResponseDto.PendenteElegivelDto toPendenteElegivelDto(
            OperacaoPainelService.PendenteElegivelResumo item) {
        return new OperacaoPainelResponseDto.PendenteElegivelDto(
                item.pedidoId(), item.criadoEm(), item.quantidadeGaloes(), item.janelaTipo());
    }

    private static OperacaoPainelResponseDto.ConfirmadoSecundariaDto toConfirmadoSecundariaDto(
            OperacaoPainelService.ConfirmadoSecundariaResumo item) {
        return new OperacaoPainelResponseDto.ConfirmadoSecundariaDto(
                item.pedidoId(), item.rotaId(), item.ordemNaRota(), item.entregadorId(), item.quantidadeGaloes());
    }

    private static OperacaoPainelResponseDto.EmRotaPrimariaDto toEmRotaPrimariaDto(
            OperacaoPainelService.EmRotaPrimariaResumo item) {
        return new OperacaoPainelResponseDto.EmRotaPrimariaDto(
                item.pedidoId(),
                item.rotaId(),
                item.entregaId(),
                item.entregadorId(),
                item.quantidadeGaloes(),
                item.statusEntrega());
    }
}
