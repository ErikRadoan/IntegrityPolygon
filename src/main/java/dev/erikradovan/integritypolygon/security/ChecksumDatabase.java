package dev.erikradovan.integritypolygon.security;

import java.util.Optional;

/**
 * Interface for looking up expected checksums of module JARs.
 */
public interface ChecksumDatabase {
    /**
     * Get the expected SHA-256 checksum for a module file.
     *
     * @param moduleFileName the JAR filename
     * @return the expected checksum, or empty if unknown
     */
    Optional<String> getChecksum(String moduleFileName);
}

