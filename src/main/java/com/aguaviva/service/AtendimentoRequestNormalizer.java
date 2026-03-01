package com.aguaviva.service;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

final class AtendimentoRequestNormalizer {

    static final String METODO_PAGAMENTO_PADRAO = "NAO_INFORMADO";
    static final String ORIGEM_CANAL_MANUAL = "MANUAL";
    static final String ORIGEM_CANAL_WHATSAPP = "WHATSAPP";
    static final String ORIGEM_CANAL_BINA_FIXO = "BINA_FIXO";
    static final String ORIGEM_CANAL_TELEFONIA_FIXO = "TELEFONIA_FIXO";

    private static final DateTimeFormatter WINDOW_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private AtendimentoRequestNormalizer() {}

    static String normalizeExternalCallId(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("external_call_id nao pode ser nulo ou vazio");
        }

        String normalized = value.trim();
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("external_call_id deve ter no maximo 64 caracteres");
        }
        return normalized;
    }

    static String normalizePhone(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Telefone nao pode ser nulo ou vazio");
        }

        String digits = value.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            throw new IllegalArgumentException("Telefone deve ter entre 10 e 15 digitos apos normalizacao");
        }
        return digits;
    }

    static String normalizeMetodoPagamento(String value) {
        if (value == null || value.isBlank()) {
            return METODO_PAGAMENTO_PADRAO;
        }

        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case METODO_PAGAMENTO_PADRAO, "DINHEIRO", "PIX", "CARTAO", "VALE" -> normalized;
            default -> throw new IllegalArgumentException("metodoPagamento invalido");
        };
    }

    static JanelaPedidoInput normalizeJanelaPedido(String janelaTipoRaw, String janelaInicioRaw, String janelaFimRaw) {
        String janelaTipo = normalizeJanelaTipo(janelaTipoRaw);
        LocalTime janelaInicio = parseOptionalWindowTime(janelaInicioRaw, "janelaInicio");
        LocalTime janelaFim = parseOptionalWindowTime(janelaFimRaw, "janelaFim");

        if ("ASAP".equals(janelaTipo)) {
            if (janelaInicio != null || janelaFim != null) {
                throw new IllegalArgumentException("janelaInicio/janelaFim so podem ser enviados com janelaTipo=HARD");
            }
            return new JanelaPedidoInput("ASAP", null, null);
        }

        if (janelaInicio == null || janelaFim == null) {
            throw new IllegalArgumentException("janelaTipo=HARD exige janelaInicio e janelaFim no formato HH:mm");
        }
        if (!janelaFim.isAfter(janelaInicio)) {
            throw new IllegalArgumentException("janelaFim deve ser maior que janelaInicio para janelaTipo=HARD");
        }
        return new JanelaPedidoInput("HARD", janelaInicio, janelaFim);
    }

    static String normalizeOrigemCanal(String origemCanal, String sourceEventId, String manualRequestId) {
        if (origemCanal == null || origemCanal.isBlank()) {
            if (sourceEventId != null && !sourceEventId.isBlank()) {
                return ORIGEM_CANAL_TELEFONIA_FIXO;
            }
            if (manualRequestId != null && !manualRequestId.isBlank()) {
                return ORIGEM_CANAL_MANUAL;
            }
            return ORIGEM_CANAL_MANUAL;
        }

        String normalized = origemCanal.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "MANUAL" -> ORIGEM_CANAL_MANUAL;
            case "WHATSAPP", "WA" -> ORIGEM_CANAL_WHATSAPP;
            case "BINA", "BINA_FIXO" -> ORIGEM_CANAL_BINA_FIXO;
            case "TELEFONIA", "TELEFONIA_FIXO", "LIGACAO_FIXA" -> ORIGEM_CANAL_TELEFONIA_FIXO;
            default -> throw new IllegalArgumentException("origemCanal invalido");
        };
    }

    static String normalizeSourceEventIdOpcional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("sourceEventId/manualRequestId deve ter no maximo 128 caracteres");
        }
        return trimmed;
    }

    static void validarConsistenciaCanalEChaves(String origemCanal, String sourceEventId, String manualRequestId) {
        if (ORIGEM_CANAL_MANUAL.equals(origemCanal) && sourceEventId != null) {
            throw new IllegalArgumentException("sourceEventId nao pode ser usado com origemCanal=MANUAL");
        }
        if (!ORIGEM_CANAL_MANUAL.equals(origemCanal) && sourceEventId == null) {
            throw new IllegalArgumentException("sourceEventId obrigatorio para origemCanal automatica");
        }
        if (!ORIGEM_CANAL_MANUAL.equals(origemCanal) && manualRequestId != null) {
            throw new IllegalArgumentException("manualRequestId so pode ser usado com origemCanal=MANUAL");
        }
    }

    static CadastroClienteInput normalizeCadastroClienteInput(
            String nomeCliente, String endereco, Double latitude, Double longitude) {
        String nomeNormalizado = normalizeOptionalText(nomeCliente);
        String enderecoNormalizado = normalizeOptionalText(endereco);
        if ((latitude == null) != (longitude == null)) {
            throw new IllegalArgumentException("latitude e longitude devem ser informadas juntas");
        }
        return new CadastroClienteInput(nomeNormalizado, enderecoNormalizado, latitude, longitude);
    }

    static String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static void validateQuantidade(int quantidadeGaloes) {
        if (quantidadeGaloes <= 0) {
            throw new IllegalArgumentException("Quantidade de galoes deve ser maior que zero");
        }
    }

    static void validateAtendenteId(int atendenteId) {
        if (atendenteId <= 0) {
            throw new IllegalArgumentException("AtendenteId deve ser maior que zero");
        }
    }

    static String formatWindowTime(LocalTime value) {
        if (value == null) {
            return null;
        }
        return value.format(WINDOW_TIME_FORMATTER);
    }

    private static String normalizeJanelaTipo(String janelaTipoRaw) {
        if (janelaTipoRaw == null || janelaTipoRaw.isBlank()) {
            return "ASAP";
        }

        String normalized = janelaTipoRaw.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "ASAP", "FLEX", "FLEXIVEL", "LIVRE" -> "ASAP";
            case "HARD" -> "HARD";
            default -> throw new IllegalArgumentException("janelaTipo invalido");
        };
    }

    private static LocalTime parseOptionalWindowTime(String value, String fieldName) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return LocalTime.parse(trimmed, WINDOW_TIME_FORMATTER);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(fieldName + " deve estar no formato HH:mm");
        }
    }

    record CadastroClienteInput(String nomeCliente, String endereco, Double latitude, Double longitude) {}

    record JanelaPedidoInput(String tipo, LocalTime inicio, LocalTime fim) {}
}
