package com.aguaviva.domain.cliente;

import java.math.BigDecimal;
import java.util.Objects;

public final class Cliente {

    private static final BigDecimal MIN_LATITUDE = BigDecimal.valueOf(-90);
    private static final BigDecimal MAX_LATITUDE = BigDecimal.valueOf(90);
    private static final BigDecimal MIN_LONGITUDE = BigDecimal.valueOf(-180);
    private static final BigDecimal MAX_LONGITUDE = BigDecimal.valueOf(180);

    private final int id;
    private final String nome;
    private final String telefone;
    private final ClienteTipo tipo;
    private final String endereco;
    private final BigDecimal latitude;
    private final BigDecimal longitude;
    private final String notas;

    public Cliente(int id, String nome, String telefone, ClienteTipo tipo, String endereco,
                   BigDecimal latitude, BigDecimal longitude, String notas) {
        validarId(id);
        validarNome(nome);
        validarTelefone(telefone);
        Objects.requireNonNull(tipo, "Tipo nao pode ser nulo");
        validarEndereco(endereco);
        validarCoordenadas(latitude, longitude);

        this.id = id;
        this.nome = nome.trim();
        this.telefone = telefone.trim();
        this.tipo = tipo;
        this.endereco = endereco.trim();
        this.latitude = latitude;
        this.longitude = longitude;
        this.notas = normalizarNotas(notas);
    }

    public Cliente(String nome, String telefone, ClienteTipo tipo, String endereco,
                   BigDecimal latitude, BigDecimal longitude, String notas) {
        this(0, nome, telefone, tipo, endereco, latitude, longitude, notas);
    }

    public Cliente(String nome, String telefone, ClienteTipo tipo, String endereco) {
        this(0, nome, telefone, tipo, endereco, null, null, null);
    }

    public int getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getTelefone() {
        return telefone;
    }

    public ClienteTipo getTipo() {
        return tipo;
    }

    public String getEndereco() {
        return endereco;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public String getNotas() {
        return notas;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Cliente cliente = (Cliente) o;
        if (this.id == 0 || cliente.id == 0) return false;
        return this.id == cliente.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "Cliente{id=" + id
                + ", nome='" + nome + "'"
                + ", telefone='" + telefone + "'"
                + ", tipo=" + tipo + "}";
    }

    private static void validarId(int id) {
        if (id < 0) {
            throw new IllegalArgumentException("Id nao pode ser negativo");
        }
    }

    private static void validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Nome nao pode ser nulo ou vazio");
        }
    }

    private static void validarTelefone(String telefone) {
        if (telefone == null || telefone.isBlank()) {
            throw new IllegalArgumentException("Telefone nao pode ser nulo ou vazio");
        }
    }

    private static void validarEndereco(String endereco) {
        if (endereco == null || endereco.isBlank()) {
            throw new IllegalArgumentException("Endereco nao pode ser nulo ou vazio");
        }
    }

    private static void validarCoordenadas(BigDecimal latitude, BigDecimal longitude) {
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null) {
            throw new IllegalArgumentException("Latitude e longitude devem ser informadas juntas");
        }
        if (latitude.compareTo(MIN_LATITUDE) < 0 || latitude.compareTo(MAX_LATITUDE) > 0) {
            throw new IllegalArgumentException("Latitude fora do intervalo valido (-90 a 90)");
        }
        if (longitude.compareTo(MIN_LONGITUDE) < 0 || longitude.compareTo(MAX_LONGITUDE) > 0) {
            throw new IllegalArgumentException("Longitude fora do intervalo valido (-180 a 180)");
        }
    }

    private static String normalizarNotas(String notas) {
        if (notas == null || notas.isBlank()) {
            return null;
        }
        return notas.trim();
    }
}
