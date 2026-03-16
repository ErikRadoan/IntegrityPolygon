package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.HttpService;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Shared HTTP client service backed by {@link java.net.http.HttpClient}.
 */
public class HttpServiceImpl implements HttpService {

    private final HttpClient client;

    public HttpServiceImpl() {
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public Response get(String url) throws Exception {
        return get(url, Map.of());
    }

    @Override
    public Response get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "IntegrityPolygon/2.0")
                .GET();
        headers.forEach(builder::header);

        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body());
    }

    @Override
    public Response post(String url, String json) throws Exception {
        return post(url, json, Map.of());
    }

    @Override
    public Response post(String url, String json, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "IntegrityPolygon/2.0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        headers.forEach(builder::header);

        HttpResponse<String> resp = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(resp.statusCode(), resp.body());
    }

    @Override
    public CompletableFuture<Response> getAsync(String url) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "IntegrityPolygon/2.0")
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new Response(resp.statusCode(), resp.body()));
    }

    @Override
    public CompletableFuture<Response> postAsync(String url, String json) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "IntegrityPolygon/2.0")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(resp -> new Response(resp.statusCode(), resp.body()));
    }
}

