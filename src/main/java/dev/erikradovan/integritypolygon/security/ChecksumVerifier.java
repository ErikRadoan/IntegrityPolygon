package dev.erikradovan.integritypolygon.security;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Optional;

/**
 * Utility for verifying module JAR integrity via SHA-256 checksums.
 */
public final class ChecksumVerifier {

    private ChecksumVerifier() {}

    /**
     * Verify a JAR file against the checksum database.
     *
     * @param jar the JAR file to verify
     * @param db  the checksum database
     * @return true if the checksum matches, false if it doesn't or isn't in the database
     */
    public static boolean verify(File jar, ChecksumDatabase db) {
        try {
            String actual = computeSHA256(jar);
            Optional<String> expected = db.getChecksum(jar.getName());
            return expected.map(e -> e.equalsIgnoreCase(actual)).orElse(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Compute the SHA-256 hash of a file.
     */
    public static String computeSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}

