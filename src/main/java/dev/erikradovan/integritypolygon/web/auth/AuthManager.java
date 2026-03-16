package dev.erikradovan.integritypolygon.web.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import dev.erikradovan.integritypolygon.config.ConfigManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;

/**
 * JWT-based authentication manager for the web panel.
 * Generates and validates tokens. The signing secret is persisted in config.
 */
public class AuthManager {

    private static final String ISSUER = "IntegrityPolygon";
    private static final long TOKEN_EXPIRY_HOURS = 24;

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final ConfigManager configManager;

    public AuthManager(ConfigManager configManager) {
        this.configManager = configManager;

        String secret = getOrCreateSecret();
        this.algorithm = Algorithm.HMAC256(secret);
        this.verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
    }

    /**
     * Generate a JWT token for a username.
     */
    public String generateToken(String username) {
        return JWT.create()
                .withIssuer(ISSUER)
                .withSubject(username)
                .withIssuedAt(Instant.now())
                .withExpiresAt(Instant.now().plus(TOKEN_EXPIRY_HOURS, ChronoUnit.HOURS))
                .sign(algorithm);
    }

    /**
     * Validate a JWT token and return the decoded claims.
     */
    public Optional<DecodedJWT> validateToken(String token) {
        try {
            DecodedJWT decoded = verifier.verify(token);
            return Optional.of(decoded);
        } catch (JWTVerificationException e) {
            return Optional.empty();
        }
    }

    /**
     * Extract the username from a valid token.
     */
    public Optional<String> getUsername(String token) {
        return validateToken(token).map(DecodedJWT::getSubject);
    }

    private String getOrCreateSecret() {
        Optional<String> existing = configManager.getValue("web.jwt_secret");
        if (existing.isPresent() && !existing.get().isEmpty()) {
            return existing.get();
        }

        // Generate a new random secret
        byte[] secretBytes = new byte[32];
        new SecureRandom().nextBytes(secretBytes);
        String secret = Base64.getEncoder().encodeToString(secretBytes);

        configManager.setValue("web.jwt_secret", secret);
        configManager.save();

        return secret;
    }
}

