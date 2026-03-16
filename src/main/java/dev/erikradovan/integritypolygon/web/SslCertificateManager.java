package dev.erikradovan.integritypolygon.web;

import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates and manages a self-signed SSL certificate for the web panel.
 * <p>
 * On first launch, uses the JDK's {@code keytool} to generate a self-signed
 * certificate and store it in a PKCS12 keystore. Subsequent launches re-use
 * the existing keystore.
 */
public class SslCertificateManager {

    private static final String KEYSTORE_FILENAME = "webpanel.p12";
    private static final String KEYSTORE_PASSWORD = "integritypolygon";
    private static final int CERT_VALIDITY_DAYS = 3650; // 10 years

    private final Path keystorePath;
    private final Logger logger;

    public SslCertificateManager(Path dataDirectory, Logger logger) {
        this.keystorePath = dataDirectory.resolve(KEYSTORE_FILENAME);
        this.logger = logger;
    }

    /**
     * Ensures a keystore with a self-signed certificate exists.
     * Creates one if it doesn't exist yet.
     *
     * @return the path to the keystore file, or null on failure
     */
    public Path ensureKeystore() {
        if (Files.exists(keystorePath)) {
            return keystorePath;
        }

        try {
            logger.info("Generating self-signed SSL certificate for web panel...");
            generateKeystoreViaKeytool();

            if (Files.exists(keystorePath)) {
                logger.info("SSL certificate created: {}", keystorePath.getFileName());
                return keystorePath;
            } else {
                logger.warn("keytool did not produce a keystore file");
                return null;
            }
        } catch (Exception e) {
            logger.warn("Failed to generate SSL certificate: {}", e.getMessage());
            return null;
        }
    }

    public String getKeystorePassword() {
        return KEYSTORE_PASSWORD;
    }

    public Path getKeystorePath() {
        return keystorePath;
    }

    private void generateKeystoreViaKeytool() throws Exception {
        // Find keytool in the current JDK
        String javaHome = System.getProperty("java.home");
        Path keytoolPath = Path.of(javaHome, "bin", "keytool");

        // On Windows, try keytool.exe
        if (!Files.exists(keytoolPath)) {
            keytoolPath = Path.of(javaHome, "bin", "keytool.exe");
        }

        if (!Files.exists(keytoolPath)) {
            throw new RuntimeException("keytool not found in " + javaHome + "/bin");
        }

        // Ensure parent directory exists
        Files.createDirectories(keystorePath.getParent());

        ProcessBuilder pb = new ProcessBuilder(
                keytoolPath.toString(),
                "-genkeypair",
                "-alias", "integritypolygon",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", String.valueOf(CERT_VALIDITY_DAYS),
                "-storetype", "PKCS12",
                "-keystore", keystorePath.toAbsolutePath().toString(),
                "-storepass", KEYSTORE_PASSWORD,
                "-keypass", KEYSTORE_PASSWORD,
                "-dname", "CN=IntegrityPolygon, O=IntegrityPolygon, L=Server"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes());
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("keytool failed (exit " + exitCode + "): " + output.trim());
        }
    }
}
