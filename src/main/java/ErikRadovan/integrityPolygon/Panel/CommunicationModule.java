package ErikRadovan.integrityPolygon.Panel;

import ErikRadovan.integrityPolygon.Config.Config;
import ErikRadovan.integrityPolygon.Logging.LogEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.ably.lib.realtime.AblyRealtime;
import io.ably.lib.realtime.Channel;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.ClientOptions;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.*;
import java.util.Base64;

public class CommunicationModule {
    private static String rsaPublicKey = null;
    private static final List<LogEvent> logCache = new ArrayList<>();
    private AblyRealtime ably;
    private static String userId;

    public CommunicationModule(String ablyApiKey) throws AblyException {
        Optional<Config.UserCredentials> creds = Config.loadSessionCredentials();

        if (creds.isPresent()) {
            userId = creds.get().userId();
            rsaPublicKey = creds.get().publicKey();
        } else {
            ClientOptions options = new ClientOptions(ablyApiKey);
            this.ably = new AblyRealtime(options);
            try {
                SubmitToken();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

        }




    }

    public void SubmitToken() throws Exception {

        String token = TokenGenerator.generateEncryptedToken();

        System.out.println("Please sign up at: https://integritypolygon-panel.vercel.app/auth?token=" + token);

        // Use the static public key from TokenGenerator
        PublicKey publicKey = TokenGenerator.PUBLIC_KEY;

        // Encrypt the token using hybrid encryption (AES + RSA)
        HybridEncryptedPayload payload = encryptWithHybridMethod(token, publicKey);

        // Ably channel setup to receive keys
        String channelName = "token:" + token;
        Channel channel = ably.channels.get(channelName);
        channel.subscribe(msg -> {
            try {
                // msg.data is a JSON string; parse it properly
                String jsonString = msg.data.toString();
                JsonObject json = new Gson().fromJson(jsonString, JsonObject.class);
                JsonElement dataField = json.get("message");

                if (dataField != null && dataField.isJsonPrimitive()) {
                    String message = dataField.getAsString();
                    if (message.startsWith("KEYS:")) {
                        String[] parts = message.replace("KEYS:", "").split(":", 2);
                        if (parts.length == 2) {
                            userId = parts[0];
                            rsaPublicKey = parts[1];
                            Config.saveSessionCredentials(userId, rsaPublicKey);
                            for (LogEvent log : logCache) {
                                SendLog(log);
                            }
                            logCache.clear();
                            System.out.println("\u001B[38;5;82mSuccessfully registered with the server!");
                        } else {
                            System.out.println("Malformed KEYS message.");
                        }
                    }
                } else {
                    System.out.println("Unexpected message format from Ably: " + jsonString);
                }
            } catch (Exception e) {
                System.out.println("Failed to parse message: " + e.getMessage());
                e.printStackTrace();
            }
        });

        // Send encrypted payload to panel
        URL url = new URL("https://integritypolygon-panel.vercel.app/api/token");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        String jsonInputString = String.format("""
        {
          "encryptedMessage": "%s",
          "encryptedAESKey": "%s",
          "iv": "%s"
        }
        """, payload.encryptedMessage(), payload.encryptedAESKey(), payload.iv());

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonInputString.getBytes("utf-8");
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode != HttpURLConnection.HTTP_OK) {
            System.out.println("Failed to submit token. Response code: " + responseCode);
            try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8)) {
                String errorResponse = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                System.out.println("Error Response: " + errorResponse);
            }

        }
    }

    public void SendLog(LogEvent logEvent) {
        if (rsaPublicKey == null || userId == null) {
            logCache.add(logEvent);
            return;
        }

        try {
            Key dynamicKey = LoadRSAPublicKey(); // This is correct if you're encrypting with public key
            String jsonLog = new Gson().toJson(logEvent);
            HybridEncryptedPayload payload = encryptWithHybridMethod(jsonLog, dynamicKey);

            String payloadToSend = new Gson().toJson(Map.of(
                    "userId", userId,
                    "encryptedMessage", payload.encryptedMessage(),
                    "encryptedAESKey", payload.encryptedAESKey(),
                    "iv", payload.iv()
            ));

            URL url = new URL("https://integritypolygon-panel.vercel.app/api/post_log");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = payloadToSend.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                System.err.println("Failed to send log. Server responded with code: " + responseCode);

                try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8)) {
                    String errorResponse = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                    System.err.println("Server Error: " + errorResponse);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private record HybridEncryptedPayload(String encryptedMessage, String encryptedAESKey, String iv) {}

    private static HybridEncryptedPayload encryptWithHybridMethod(String plainText, Key rsaEncryptionKey) throws Exception {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(256);
        SecretKey aesKey = keyGen.generateKey();

        byte[] ivBytes = new byte[16];
        new SecureRandom().nextBytes(ivBytes);
        IvParameterSpec iv = new IvParameterSpec(ivBytes);

        Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aesCipher.init(Cipher.ENCRYPT_MODE, aesKey, iv);
        byte[] encryptedMessageBytes = aesCipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

        Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");
        rsaCipher.init(Cipher.ENCRYPT_MODE, rsaEncryptionKey);
        byte[] encryptedKeyBytes = rsaCipher.doFinal(aesKey.getEncoded());

        return new HybridEncryptedPayload(
                Base64.getEncoder().encodeToString(encryptedMessageBytes),
                Base64.getEncoder().encodeToString(encryptedKeyBytes),
                Base64.getEncoder().encodeToString(ivBytes)
        );
    }

    private static PublicKey LoadRSAPublicKey() throws Exception {
        if (rsaPublicKey == null) {
            throw new IllegalStateException("RSA key string is null");
        }

        // Clean up the PEM
        String cleanedKey = rsaPublicKey
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

        byte[] keyBytes = Base64.getDecoder().decode(cleanedKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }

}
