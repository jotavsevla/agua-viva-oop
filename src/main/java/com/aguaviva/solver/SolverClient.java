package com.aguaviva.solver;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

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
        // FastAPI/Uvicorn em HTTP claro pode rejeitar corpo quando o client tenta upgrade h2c.
        // Forcamos HTTP/1.1 para evitar perda de body e garantir compatibilidade.
        this.httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
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
            throw new IOException("Solver retornou status " + httpResp.statusCode() + ": " + httpResp.body());
        }

        return gson.fromJson(httpResp.body(), SolverResponse.class);
    }

    public SolverAsyncAccepted submitAsync(SolverRequest request) throws IOException, InterruptedException {
        String body = gson.toJson(request);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(solverUrl + "/solve/async"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (httpResp.statusCode() != 200 && httpResp.statusCode() != 202) {
            throw new IOException("Solver async retornou status " + httpResp.statusCode() + ": " + httpResp.body());
        }

        return gson.fromJson(httpResp.body(), SolverAsyncAccepted.class);
    }

    public boolean cancel(String jobId) throws IOException, InterruptedException {
        Objects.requireNonNull(jobId, "jobId nao pode ser nulo");
        if (jobId.isBlank()) {
            throw new IllegalArgumentException("jobId nao pode ser vazio");
        }

        String encoded = URLEncoder.encode(jobId, StandardCharsets.UTF_8);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(solverUrl + "/cancel/" + encoded))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (httpResp.statusCode() == 404) {
            return false;
        }
        if (httpResp.statusCode() != 200) {
            throw new IOException("Cancelamento retornou status " + httpResp.statusCode() + ": " + httpResp.body());
        }
        return true;
    }

    public SolverJobResult getResult(String jobId) throws IOException, InterruptedException {
        Objects.requireNonNull(jobId, "jobId nao pode ser nulo");
        if (jobId.isBlank()) {
            throw new IllegalArgumentException("jobId nao pode ser vazio");
        }

        String encoded = URLEncoder.encode(jobId, StandardCharsets.UTF_8);
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(solverUrl + "/result/" + encoded))
                .GET()
                .build();

        HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());
        if (httpResp.statusCode() == 404) {
            return null;
        }
        if (httpResp.statusCode() != 200) {
            throw new IOException(
                    "Consulta de resultado retornou status " + httpResp.statusCode() + ": " + httpResp.body());
        }

        return gson.fromJson(httpResp.body(), SolverJobResult.class);
    }

    public void cancelBestEffort(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return;
        }
        try {
            cancel(jobId);
        } catch (Exception ignored) {
            // Melhor esforco: cancelamento nao deve derrubar fluxo de replanejamento.
        }
    }
}
