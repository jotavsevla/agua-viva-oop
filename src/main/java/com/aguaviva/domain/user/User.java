package com.aguaviva.domain.user;

import java.util.Objects;

public final class User {

    private final int id;
    private final String nome;
    private final String email;
    private final Password password;
    private final UserPapel papel;
    private final String telefone;
    private final boolean ativo;

    public User(int id, String nome, String email, Password password,
                UserPapel papel, String telefone, boolean ativo) {
        validarNome(nome);
        validarEmail(email);
        Objects.requireNonNull(password, "Password nao pode ser nulo");
        Objects.requireNonNull(papel, "Papel nao pode ser nulo");

        this.id = id;
        this.nome = nome.trim();
        this.email = email.trim().toLowerCase();
        this.password = password;
        this.papel = papel;
        this.telefone = telefone;
        this.ativo = ativo;
    }

    public User(String nome, String email, Password password, UserPapel papel) {
        this(0, nome, email, password, papel, null, true);
    }

    // ========================================================================
    // Comportamento
    // ========================================================================

    public boolean podeGerenciar(User outro) {
        return this.papel.podeGerenciar(outro.papel);
    }

    public boolean verificarSenha(String senhaPlana) {
        return this.password.matches(senhaPlana);
    }

    // ========================================================================
    // Acessores (sem setters)
    // ========================================================================

    public int getId() {
        return id;
    }

    public String getNome() {
        return nome;
    }

    public String getEmail() {
        return email;
    }

    public UserPapel getPapel() {
        return papel;
    }

    public String getTelefone() {
        return telefone;
    }

    public boolean isAtivo() {
        return ativo;
    }

    public String toSenhaHash() {
        return password.toHash();
    }

    // ========================================================================
    // Identidade (entidade — baseada em id)
    // ========================================================================

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        User user = (User) o;
        if (this.id == 0 || user.id == 0) return false;
        return this.id == user.id;
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "User{id=" + id
                + ", nome='" + nome + "'"
                + ", email='" + email + "'"
                + ", papel=" + papel
                + ", ativo=" + ativo + "}";
    }

    // ========================================================================
    // Validacao (privada)
    // ========================================================================

    private static void validarNome(String nome) {
        if (nome == null || nome.isBlank()) {
            throw new IllegalArgumentException("Nome nao pode ser nulo ou vazio");
        }
    }

    private static void validarEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email nao pode ser nulo ou vazio");
        }
        if (!email.contains("@")) {
            throw new IllegalArgumentException("Email invalido");
        }
    }
}
