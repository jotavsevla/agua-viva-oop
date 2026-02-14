package com.aguaviva.domain.user;

import java.util.Objects;
import org.mindrot.jbcrypt.BCrypt;

public final class Password {

    private static final int MINIMUM_LENGTH = 8;
    private static final int BCRYPT_ROUNDS = 12;

    private final String hash;

    private Password(String hash) {
        this.hash = hash;
    }

    public static Password fromPlainText(String plainText) {
        validate(plainText);
        String hash = BCrypt.hashpw(plainText, BCrypt.gensalt(BCRYPT_ROUNDS));
        return new Password(hash);
    }

    public static Password fromHash(String hash) {
        if (hash == null || hash.isBlank()) {
            throw new IllegalArgumentException("Hash nao pode ser nulo ou vazio");
        }
        return new Password(hash);
    }

    public boolean matches(String plainText) {
        if (plainText == null) {
            return false;
        }
        return BCrypt.checkpw(plainText, this.hash);
    }

    public static void validate(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("Senha nao pode ser nula ou vazia");
        }
        if (plainText.length() < MINIMUM_LENGTH) {
            throw new IllegalArgumentException("Senha deve ter no minimo " + MINIMUM_LENGTH + " caracteres");
        }
    }

    public String toHash() {
        return this.hash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Password password = (Password) o;
        return Objects.equals(hash, password.hash);
    }

    @Override
    public int hashCode() {
        return Objects.hash(hash);
    }

    @Override
    public String toString() {
        return "Password[****]";
    }
}
