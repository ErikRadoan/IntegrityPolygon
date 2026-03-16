package dev.erikradovan.integritypolygon.security;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Fetches module checksums from a remote JSON endpoint.
 */
public class RemoteChecksumDatabase implements ChecksumDatabase {

    private final Map<String, String> checksums;

    public RemoteChecksumDatabase(String remoteUrl) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(remoteUrl))
                .header("User-Agent", "IntegrityPolygon/2.0")
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch checksums. HTTP " + response.statusCode());
        }

        this.checksums = new Gson().fromJson(response.body(), new TypeToken<Map<String, String>>() {}.getType());
    }

    @Override
    public Optional<String> getChecksum(String moduleFileName) {
        return Optional.ofNullable(checksums.get(moduleFileName));
    }
}

