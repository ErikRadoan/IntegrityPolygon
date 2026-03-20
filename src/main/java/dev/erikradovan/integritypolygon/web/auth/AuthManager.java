package dev.erikradovan.integritypolygon.web.auth;

import dev.erikradovan.integritypolygon.config.ConfigManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-based authentication manager for the web panel.
 * Creates, validates, and invalidates short-lived server-side sessions.
 */
public class AuthManager {

    private static final long SESSION_EXPIRY_HOURS = 24;

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public AuthManager(ConfigManager configManager) {
        // Config currently unused for session auth but constructor is kept stable.
    }

    /**
     * Create a new session ID for a username + role.
     */
    public String createSession(String username, String role) {
        cleanupExpired();
        String sessionId = generateSessionId();
        Instant expiresAt = Instant.now().plus(SESSION_EXPIRY_HOURS, ChronoUnit.HOURS);
        sessions.put(sessionId, new SessionRecord(username, normalizeRole(role), expiresAt));
        return sessionId;
    }

    /**
     * Validate a session ID and return session metadata.
     */
    public Optional<SessionRecord> validateSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }

        SessionRecord session = sessions.get(sessionId);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(sessionId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void invalidateSession(String sessionId) {
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
    }

    public void invalidateUserSessions(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        sessions.entrySet().removeIf(e -> username.equalsIgnoreCase(e.getValue().username()));
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "admin";
        }
        return role;
    }

    private String generateSessionId() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void cleanupExpired() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt().isBefore(now));
    }

    public record SessionRecord(String username, String role, Instant expiresAt) {
        public SessionRecord {
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("username cannot be blank");
            }
            if (role == null || role.isBlank()) {
                role = "admin";
            }
        }
    }
}

