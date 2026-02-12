package com.aguaviva.solver;

public final class Coordenada {

    private final double lat;
    private final double lon;

    public Coordenada(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }

    public double getLat() {
        return lat;
    }

    public double getLon() {
        return lon;
    }

    @Override
    public String toString() {
        return "Coordenada{lat=" + lat + ", lon=" + lon + "}";
    }
}
