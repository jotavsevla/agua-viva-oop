package com.aguaviva.domain.user;

public enum UserPapel {
    ENTREGADOR(1),
    ATENDENTE(2),
    ADMIN(3),
    SUPERVISOR(4);

    private final int nivel;

    UserPapel(int nivel) {
        this.nivel = nivel;
    }

    public boolean podeGerenciar(UserPapel outro) {
        return this.nivel > outro.nivel;
    }
}
