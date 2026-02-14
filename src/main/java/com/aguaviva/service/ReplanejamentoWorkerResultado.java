package com.aguaviva.service;

public record ReplanejamentoWorkerResultado(
        int eventosProcessados, boolean replanejou, int rotasCriadas, int entregasCriadas, int pedidosNaoAtendidos) {}
