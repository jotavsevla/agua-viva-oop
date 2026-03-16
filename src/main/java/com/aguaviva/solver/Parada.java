package com.aguaviva.solver;

public record Parada(int ordem, int pedidoId, double lat, double lon, String horaPrevista) {

    @Override
    public String toString() {
        return "Parada{ordem=" + ordem + ", pedidoId=" + pedidoId + ", horaPrevista='" + horaPrevista + "'}";
    }
}
