package ErikRadovan.integrityPolygon.CheckSumLogic;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.InputStreamReader;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

public class RemoteChecksumDatabase implements ChecksumDatabase {

    private final Map<String, String> checksums;

    public RemoteChecksumDatabase(String remoteUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(remoteUrl).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("User-Agent", "IntegrityPolygon/1.0");

        if (connection.getResponseCode() != 200) {
            throw new RuntimeException("Failed to fetch checksums. HTTP " + connection.getResponseCode());
        }

        try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
            this.checksums = new Gson().fromJson(reader, new TypeToken<Map<String, String>>(){}.getType());
        }
    }

    @Override
    public Optional<String> getChecksum(String moduleFileName) {
        return Optional.ofNullable(checksums.get(moduleFileName));
    }
}
