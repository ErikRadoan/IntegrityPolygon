package ErikRadovan.integrityPolygon.Panel;

import java.io.InputStream;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;

public class TokenGenerator {
    public static final PublicKey PUBLIC_KEY = loadPublicKey();

    public static String generateEncryptedToken() {
        try {
            String token = Long.toHexString(Double.doubleToLongBits(Math.random()));

            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");

            // Explicitne nastavenie OAEP parametrov (hash = SHA-256)
            OAEPParameterSpec oaepParams = new OAEPParameterSpec(
                    "SHA-256",                    // hash algorithm for OAEP
                    "MGF1",                      // mask generation function
                    MGF1ParameterSpec.SHA256,    // hash for MGF1
                    PSource.PSpecified.DEFAULT   // PSource
            );

            cipher.init(Cipher.ENCRYPT_MODE, PUBLIC_KEY, oaepParams);

            byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(encrypted);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt token", e);
        }
    }

    private static PublicKey loadPublicKey() {
        try (InputStream input = TokenGenerator.class.getClassLoader().getResourceAsStream("private.pem.pub")) {
            assert input != null;
            String pem = new String(input.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("-----BEGIN (.*)-----", "")
                    .replaceAll("-----END (.*)-----", "")
                    .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(pem);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load public key", e);
        }
    }
}
