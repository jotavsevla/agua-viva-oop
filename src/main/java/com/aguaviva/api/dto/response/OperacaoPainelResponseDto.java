package com.aguaviva.api.dto.response;

import java.util.List;

public record OperacaoPainelResponseDto(
        String atualizadoEm,
        String ambiente,
        PedidosPorStatusDto pedidosPorStatus,
        IndicadoresEntregaDto indicadoresEntrega,
        RotasResumoDto rotas,
        FilasResumoDto filas) {

    public record PedidosPorStatusDto(int pendente, int confirmado, int emRota, int entregue, int cancelado) {}

    public record IndicadoresEntregaDto(
            int totalFinalizadas, int entregasConcluidas, int entregasCanceladas, double taxaSucessoPercentual) {}

    public record RotasResumoDto(List<RotaEmAndamentoDto> emAndamento, List<RotaPlanejadaDto> planejadas) {
        public RotasResumoDto {
            emAndamento = List.copyOf(emAndamento);
            planejadas = List.copyOf(planejadas);
        }
    }

    public record RotaEmAndamentoDto(int rotaId, int entregadorId, int pendentes, int emExecucao) {}

    public record RotaPlanejadaDto(int rotaId, int entregadorId, int pendentes) {}

    public record FilasResumoDto(
            List<PendenteElegivelDto> pendentesElegiveis,
            List<ConfirmadoSecundariaDto> confirmadosSecundaria,
            List<EmRotaPrimariaDto> emRotaPrimaria) {
        public FilasResumoDto {
            pendentesElegiveis = List.copyOf(pendentesElegiveis);
            confirmadosSecundaria = List.copyOf(confirmadosSecundaria);
            emRotaPrimaria = List.copyOf(emRotaPrimaria);
        }
    }

    public record PendenteElegivelDto(int pedidoId, String criadoEm, int quantidadeGaloes, String janelaTipo) {}

    public record ConfirmadoSecundariaDto(
            int pedidoId, int rotaId, int ordemNaRota, int entregadorId, int quantidadeGaloes) {}

    public record EmRotaPrimariaDto(
            int pedidoId, int rotaId, int entregaId, int entregadorId, int quantidadeGaloes, String statusEntrega) {}
}
