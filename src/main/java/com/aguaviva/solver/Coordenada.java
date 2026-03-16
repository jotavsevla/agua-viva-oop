package com.aguaviva.solver;

public record Coordenada(double lat, double lon) {

    @Override
    public String toString() {
        return "Coordenada{lat=" + lat + ", lon=" + lon + "}";
    }
}
