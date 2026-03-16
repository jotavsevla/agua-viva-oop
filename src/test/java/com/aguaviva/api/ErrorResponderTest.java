package com.aguaviva.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class ErrorResponderTest {

    private static HttpServer server;
    private static HttpClient client;
    private static String baseUrl;

    @BeforeAll
    static void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(
                "/send",
                exchange -> ErrorResponder.send(exchange, 418, "{\"erro\":\"teapot\",\"detalhe\":\"custom\"}"));
        server.createContext("/send-error", exchange -> ErrorResponder.sendError(exchange, 404, "Nao encontrado"));
        server.createContext("/send-internal", exchange -> ErrorResponder.sendInternalError(exchange));
        server.start();

        int port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;
        client = HttpClient.newHttpClient();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendDeveRetornarStatusEBodyCorretos() throws Exception {
        HttpResponse<String> response = request("/send");

        assertEquals(418, response.statusCode());
        assertEquals("{\"erro\":\"teapot\",\"detalhe\":\"custom\"}", response.body());
    }

    @Test
    void sendDeveIncluirHeadersCors() throws Exception {
        HttpResponse<String> response = request("/send");

        assertEquals(
                "*",
                response.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());
        assertEquals(
                "Content-Type,Idempotency-Key,X-Idempotency-Key",
                response.headers().firstValue("Access-Control-Allow-Headers").orElseThrow());
        assertEquals(
                "GET,POST,OPTIONS",
                response.headers().firstValue("Access-Control-Allow-Methods").orElseThrow());
    }

    @Test
    void sendDeveIncluirContentTypeJsonUtf8() throws Exception {
        HttpResponse<String> response = request("/send");

        assertEquals(
                "application/json; charset=utf-8",
                response.headers().firstValue("Content-Type").orElseThrow());
    }

    @Test
    void sendErrorDeveRetornarErroComStatusCorreto() throws Exception {
        HttpResponse<String> response = request("/send-error");

        assertEquals(404, response.statusCode());
        assertEquals("{\"erro\":\"Nao encontrado\"}", response.body());
    }

    @Test
    void sendInternalErrorDeveRetornarErroGenericoComStatus500() throws Exception {
        HttpResponse<String> response = request("/send-internal");

        assertEquals(500, response.statusCode());
        assertEquals("{\"erro\":\"Erro interno do servidor\"}", response.body());
    }

    @Test
    void sendInternalErrorNaoDeveVazarDetalhesDeExcecao() throws Exception {
        HttpResponse<String> response = request("/send-internal");

        assertFalse(response.body().contains("NullPointerException"));
        assertFalse(response.body().contains("stacktrace"));
        assertTrue(response.body().contains("Erro interno do servidor"));
    }

    private static HttpResponse<String> request(String path) throws Exception {
        HttpRequest httpRequest =
                HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).GET().build();

        return client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
    }
}
