package com.aguaviva.solver;

public final class Parada {

    private final int ordem;
    private final int pedidoId;
    private final double lat;
    private final double lon;
    private final String horaPrevista;  // "HH:MM"

    public Parada(int ordem, int pedidoId, double lat, double lon, String horaPrevista) {
        this.ordem = ordem;
        this.pedidoId = pedidoId;
        this.lat = lat;
        this.lon = lon;
        this.horaPrevista = horaPrevista;
    }

    public int getOrdem() { return ordem; }
    public int getPedidoId() { return pedidoId; }
    public double getLat() { return lat; }
    public double getLon() { return lon; }
    public String getHoraPrevista() { return horaPrevista; }

    @Override
    public String toString() {
        return "Parada{ordem=" + ordem
                + ", pedidoId=" + pedidoId
                + ", horaPrevista='" + horaPrevista + "'}";
    }
}
