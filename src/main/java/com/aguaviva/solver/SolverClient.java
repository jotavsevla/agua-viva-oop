package com.aguaviva.solver;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Objects;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class SolverClient {

    private final HttpClient httpClient;
    private final String solverUrl;
    private final Gson gson;

    public SolverClient(String solverUrl) {
        Objects.requireNonNull(solverUrl, "URL do solver nao pode ser nula");
        if (solverUrl.isBlank()) {
            throw new IllegalArgumentException("URL do solver nao pode ser vazia");
        }
        this.solverUrl = solverUrl;
        this.httpClient = HttpClient.newHttpClient();
        this.gson = new GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create();
    }

    /**
     * Envia pedidos ao solver Python e retorna as rotas otimizadas.
     *
     * @throws IOException se o solver estiver inacessivel ou retornar erro
     * @throws InterruptedException se a thread for interrompida
     */
    public SolverResponse solve(SolverRequest request) throws IOException, InterruptedException {
        String body = gson.toJson(request);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(solverUrl + "/solve"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

        if (httpResp.statusCode() != 200) {
            throw new IOException(
                    "Solver retornou status " + httpResp.statusCode() + ": " + httpResp.body()
            );
        }

        return gson.fromJson(httpResp.body(), SolverResponse.class);
    }
}
