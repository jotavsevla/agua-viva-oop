package com.aguaviva.solver;

public final class PedidoSolver {

    private final int pedidoId;
    private final double lat;
    private final double lon;
    private final int galoes;
    private final String janelaTipo;
    private final String janelaInicio;  // "HH:MM" ou null se ASAP
    private final String janelaFim;     // "HH:MM" ou null se ASAP
    private final int prioridade;       // 1=HARD, 2=ASAP

    public PedidoSolver(int pedidoId, double lat, double lon, int galoes,
                        String janelaTipo, String janelaInicio, String janelaFim,
                        int prioridade) {
        if (galoes < 1) {
            throw new IllegalArgumentException("Galoes deve ser >= 1");
        }
        this.pedidoId = pedidoId;
        this.lat = lat;
        this.lon = lon;
        this.galoes = galoes;
        this.janelaTipo = janelaTipo;
        this.janelaInicio = janelaInicio;
        this.janelaFim = janelaFim;
        this.prioridade = prioridade;
    }

    public int getPedidoId() { return pedidoId; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public int getGaloes() { return galoes; }
    public String getJanelaTipo() { return janelaTipo; }
    public String getJanelaInicio() { return janelaInicio; }
    public String getJanelaFim() { return janelaFim; }
    public int getPrioridade() { return prioridade; }
}
