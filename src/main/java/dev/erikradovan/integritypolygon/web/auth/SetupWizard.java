package dev.erikradovan.integritypolygon.web.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import io.javalin.http.Context;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles the first-run setup flow and authentication.
 *
 * <p><b>First-launch flow:</b>
 * <ol>
 *   <li>Plugin generates a random admin password, prints it to the server console</li>
 *   <li>User logs in at the web panel with username "admin" and the generated password</li>
 *   <li>User is forced to change their password via POST /api/auth/change-password</li>
 *   <li>User completes the setup wizard (proxy address, web panel bind, etc.)</li>
 *   <li>Setup saves all config and tells the user to restart the proxy</li>
 * </ol>
 */
public class SetupWizard {

    private static final int LOGIN_MAX_FAILURES = 5;
    private static final long LOGIN_BLOCK_MS = 5 * 60 * 1000L;
    private static final long LOGIN_STATE_TTL_MS = 30 * 60 * 1000L;
    private static final String AUTH_COOKIE_NAME = "ip_auth";
    private static final int AUTH_COOKIE_MAX_AGE_SECONDS = 24 * 60 * 60;

    private final ConfigManager configManager;
    private final AuthManager authManager;
    private final Gson gson = new Gson();
    private final PasswordHasher passwordHasher = new PasswordHasher();
    private final Map<String, LoginState> loginAttempts = new ConcurrentHashMap<>();

    public SetupWizard(ConfigManager configManager, AuthManager authManager) {
        this.configManager = configManager;
        this.authManager = authManager;
    }

    /**
     * Called on first launch to generate initial admin credentials.
     * Returns the generated plaintext password so it can be printed to console.
     *
     * @return the generated plaintext password, or null if credentials already exist
     */
    public String generateInitialCredentials() {
        // If users already exist, don't regenerate
        if (!getUsers().isEmpty()) {
            return null;
        }

        String password = generateRandomPassword(12);
        String hash = passwordHasher.hashPassword(password);

        List<Map<String, Object>> users = new ArrayList<>();
        Map<String, Object> admin = new LinkedHashMap<>();
        admin.put("username", "admin");
        admin.put("password_hash", hash);
        admin.put("role", "admin");
        admin.put("created", new Date().toString());
        users.add(admin);
        configManager.setValue("web.users", users);
        configManager.setValue("web.admin.must_change_password", true);
        configManager.save();

        return password;
    }

    /**
     * POST /api/auth/login — authenticate and issue a session cookie.
     * Expects JSON: {@code {"username":"...","password":"..."}}
     */
    public void handleLogin(Context ctx) {
        cleanupLoginAttempts();
        JsonObject body = safeBody(ctx);
        String username = body.has("username") ? body.get("username").getAsString().trim() : "";
        String password = body.has("password") ? body.get("password").getAsString() : "";

        if (username.isBlank() || password.isBlank()) {
            ctx.status(400).json(Map.of("error", "Username and password are required"));
            return;
        }

        String attemptKey = loginAttemptKey(ctx, username);
        LoginState state = loginAttempts.computeIfAbsent(attemptKey, k -> new LoginState());
        if (state.blockedUntilMs > System.currentTimeMillis()) {
            ctx.status(429).json(Map.of("error", "Too many failed attempts. Try again later."));
            return;
        }

        Optional<UserRecord> userOpt = findUserByUsername(username);
        if (userOpt.isEmpty()) {
            registerLoginFailure(state);
            ctx.status(401).json(Map.of("error", "Invalid credentials"));
            return;
        }

        UserRecord user = userOpt.get();
        boolean validPassword = passwordHasher.verify(password, user.passwordHash);
        if (!validPassword) {
            registerLoginFailure(state);
            ctx.status(401).json(Map.of("error", "Invalid credentials"));
            return;
        }

        clearLoginFailure(state);

        String sessionId = authManager.createSession(user.username, user.role);
        setAuthCookie(ctx, sessionId);

        boolean mustChangePassword = configManager.<Boolean>getValue("web.admin.must_change_password")
                .orElse(false);
        boolean setupCompleted = configManager.isSetupCompleted();

        ctx.json(Map.of(
                "username", user.username,
                "role", user.role,
                "must_change_password", mustChangePassword,
                "setup_completed", setupCompleted
        ));
    }

    /**
     * GET /api/auth/me — return current authenticated identity.
     */
    public void handleMe(Context ctx) {
        String sessionId = extractSessionId(ctx);
        Optional<AuthManager.SessionRecord> session = authManager.validateSession(sessionId);
        if (session.isEmpty()) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }
        ctx.json(Map.of(
                "username", session.get().username(),
                "role", session.get().role()
        ));
    }

    /**
     * PATCH /api/auth/account — rename the current account.
     */
    public void handleRenameAccount(Context ctx) {
        String sessionId = extractSessionId(ctx);
        Optional<AuthManager.SessionRecord> session = authManager.validateSession(sessionId);
        if (session.isEmpty()) {
            ctx.status(401).json(Map.of("error", "Unauthorized"));
            return;
        }

        JsonObject body = safeBody(ctx);
        String newUsername = body.has("username") ? body.get("username").getAsString().trim() : "";
        if (newUsername.isBlank() || newUsername.length() < 3) {
            ctx.status(400).json(Map.of("error", "Username must be at least 3 characters"));
            return;
        }

        String oldUsername = session.get().username();
        List<Map<String, Object>> users = getUsers();

        for (Map<String, Object> u : users) {
            String uname = String.valueOf(u.get("username"));
            if (!uname.equalsIgnoreCase(oldUsername) && uname.equalsIgnoreCase(newUsername)) {
                ctx.status(409).json(Map.of("error", "Username already exists"));
                return;
            }
        }

        boolean updated = false;
        String role = session.get().role();
        for (Map<String, Object> u : users) {
            if (oldUsername.equalsIgnoreCase(String.valueOf(u.get("username")))) {
                u.put("username", newUsername);
                role = String.valueOf(u.getOrDefault("role", role));
                updated = true;
                break;
            }
        }

        if (!updated) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }

        configManager.setValue("web.users", users);
        configManager.save();

        authManager.invalidateUserSessions(oldUsername);
        String refreshedSession = authManager.createSession(newUsername, role);
        setAuthCookie(ctx, refreshedSession);

        ctx.json(Map.of("success", true, "username", newUsername, "role", role));
    }

    /**
     * POST /api/auth/change-password — change the admin password.
     * Expects JSON: {@code {"current_password":"...","new_password":"..."}}
     * Requires a valid auth cookie session.
     */
    public void handleChangePassword(Context ctx) {
        JsonObject body = safeBody(ctx);
        String currentPassword = body.has("current_password") ? body.get("current_password").getAsString() : "";
        String newPassword = body.has("new_password") ? body.get("new_password").getAsString() : "";

        if (newPassword.isBlank() || newPassword.length() < 8) {
            ctx.status(400).json(Map.of("error", "New password must be at least 8 characters"));
            return;
        }

        String sessionId = extractSessionId(ctx);
        Optional<AuthManager.SessionRecord> session = authManager.validateSession(sessionId);
        if (session.isEmpty()) {
            ctx.status(401).json(Map.of("error", "Invalid or expired session"));
            return;
        }

        Optional<UserRecord> userOpt = findUserByUsername(session.get().username());
        if (userOpt.isEmpty()) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }

        UserRecord user = userOpt.get();
        if (!passwordHasher.verify(currentPassword, user.passwordHash)) {
            ctx.status(401).json(Map.of("error", "Current password is incorrect"));
            return;
        }

        updateUserPassword(user.username, newPassword);
        configManager.setValue("web.admin.must_change_password", false);
        configManager.save();

        authManager.invalidateUserSessions(user.username);
        String refreshedSession = authManager.createSession(user.username, user.role);
        setAuthCookie(ctx, refreshedSession);

        ctx.json(Map.of("success", true, "message", "Password changed successfully"));
    }

    /**
     * POST /api/auth/logout — clear the auth cookie.
     */
    public void handleLogout(Context ctx) {
        String sessionId = extractSessionId(ctx);
        if (!sessionId.isBlank()) {
            authManager.invalidateSession(sessionId);
        }
        clearAuthCookie(ctx);
        ctx.json(Map.of("success", true));
    }

    /**
     * POST /api/setup — complete the initial setup wizard.
     * Requires authentication (user must have logged in and changed password first).
     * <p>
     * Expects JSON:
     * <pre>{@code {
     *   "proxy_host": "omega.goodhost.cz",
     *   "proxy_port": 4342,
     *   "web_bind": "0.0.0.0",
     *   "web_port": 3490,
     *   "server_name": "My Network"
     * }}</pre>
     */
    public void handleSetup(Context ctx) {
        if (configManager.isSetupCompleted()) {
            ctx.status(403).json(Map.of("error", "Setup already completed"));
            return;
        }

        // Don't allow setup if password hasn't been changed yet
        boolean mustChange = configManager.<Boolean>getValue("web.admin.must_change_password").orElse(false);
        if (mustChange) {
            ctx.status(403).json(Map.of("error", "You must change your password before completing setup"));
            return;
        }

        JsonObject body = safeBody(ctx);

        // Proxy server info (required — we can't detect this automatically)
        if (!body.has("proxy_host") || body.get("proxy_host").getAsString().isBlank()) {
            ctx.status(400).json(Map.of("error", "proxy_host is required (e.g., omega.goodhost.cz)"));
            return;
        }
        if (!body.has("proxy_port")) {
            ctx.status(400).json(Map.of("error", "proxy_port is required (e.g., 4342)"));
            return;
        }

        // Web panel bind info (required — we need to know what address/port to serve on)
        if (!body.has("web_port")) {
            ctx.status(400).json(Map.of("error", "web_port is required (e.g., 3490)"));
            return;
        }

        // Save all settings
        configManager.setValue("proxy.host", body.get("proxy_host").getAsString().trim());
        configManager.setValue("proxy.port", body.get("proxy_port").getAsInt());

        String webBind = body.has("web_bind") ? body.get("web_bind").getAsString().trim() : "0.0.0.0";
        configManager.setValue("web.bind", webBind);
        configManager.setValue("web.port", body.get("web_port").getAsInt());

        // Extender TCP port (for Paper server communication)
        if (body.has("extender_port")) {
            configManager.setValue("extender.port", body.get("extender_port").getAsInt());
        }

        if (body.has("server_name") && !body.get("server_name").getAsString().isBlank()) {
            configManager.setValue("server.name", body.get("server_name").getAsString().trim());
        }

        // Optional: repository URL override
        if (body.has("repository_url") && !body.get("repository_url").getAsString().isBlank()) {
            configManager.setValue("modules.repository_url", body.get("repository_url").getAsString().trim());
        }

        // Mark setup as complete
        configManager.setValue("setup.completed", true);
        configManager.save();

        ctx.json(Map.of(
                "success", true,
                "message", "Setup completed! Please restart the Velocity proxy for changes to take effect.",
                "restart_required", true
        ));
    }

    /**
     * GET /api/setup/status — returns current setup state and what fields are needed.
     */
    public void handleSetupStatus(Context ctx) {
        try {
            boolean setupCompleted = configManager.isSetupCompleted();
            boolean mustChangePassword = configManager.<Boolean>getValue("web.admin.must_change_password").orElse(false);
            boolean hasCredentials = configManager.getValue("web.users")
                    .map(v -> v instanceof List<?> list && !list.isEmpty())
                    .orElse(false);
            int extenderPort;
            try {
                extenderPort = configManager.getExtenderPort();
            } catch (Exception ignored) {
                extenderPort = 3491;
            }

            Map<String, Object> result = new java.util.LinkedHashMap<>();
            result.put("setup_completed", setupCompleted);
            result.put("has_credentials", hasCredentials);
            result.put("must_change_password", mustChangePassword);
            result.put("extender_port", extenderPort);
            result.put("required_fields", setupCompleted
                    ? Map.of()
                    : Map.of(
                            "proxy_host", "The hostname/IP of your Velocity proxy (e.g., omega.goodhost.cz)",
                            "proxy_port", "The port your Velocity proxy runs on (e.g., 4342)",
                            "web_bind", "The address to bind the web panel to (default: 0.0.0.0)",
                            "web_port", "The port for the web panel (e.g., 3490)",
                            "extender_port", "The TCP port for extender connections from Paper servers (e.g., 3491)",
                            "server_name", "A friendly name for your server/network (optional)"
                    ));
            ctx.json(result);
        } catch (Exception ignored) {
            ctx.status(200).json(Map.of(
                    "setup_completed", false,
                    "has_credentials", false,
                    "must_change_password", false,
                    "extender_port", 3491,
                    "required_fields", Map.of()
            ));
        }
    }

    // ──────── Utilities ────────

    private JsonObject safeBody(Context ctx) {
        try {
            JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
            return body != null ? body : new JsonObject();
        } catch (Exception ignored) {
            return new JsonObject();
        }
    }

    private String extractSessionId(Context ctx) {
        String cookieToken = ctx.cookie(AUTH_COOKIE_NAME);
        return cookieToken != null ? cookieToken : "";
    }

    private void setAuthCookie(Context ctx, String token) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(AUTH_COOKIE_NAME).append("=").append(token)
                .append("; Path=/; Max-Age=").append(AUTH_COOKIE_MAX_AGE_SECONDS)
                .append("; HttpOnly; SameSite=Strict; Secure");
        ctx.header("Set-Cookie", cookie.toString());
    }

    private void clearAuthCookie(Context ctx) {
        StringBuilder cookie = new StringBuilder();
        cookie.append(AUTH_COOKIE_NAME).append("=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict; Secure");
        ctx.header("Set-Cookie", cookie.toString());
    }

    private String loginAttemptKey(Context ctx, String username) {
        return ctx.ip() + "|" + username.toLowerCase();
    }

    private void cleanupLoginAttempts() {
        long now = System.currentTimeMillis();
        loginAttempts.entrySet().removeIf(e -> e.getValue().lastSeenMs + LOGIN_STATE_TTL_MS < now);
    }

    private void registerLoginFailure(LoginState state) {
        state.failures++;
        state.lastSeenMs = System.currentTimeMillis();
        if (state.failures >= LOGIN_MAX_FAILURES) {
            state.blockedUntilMs = System.currentTimeMillis() + LOGIN_BLOCK_MS;
            state.failures = 0;
        }
    }

    private void clearLoginFailure(LoginState state) {
        state.failures = 0;
        state.blockedUntilMs = 0L;
        state.lastSeenMs = System.currentTimeMillis();
    }

    private Optional<UserRecord> findUserByUsername(String username) {
        for (Map<String, Object> user : getUsers()) {
            String uname = String.valueOf(user.get("username"));
            if (uname.equalsIgnoreCase(username)) {
                String role = String.valueOf(user.getOrDefault("role", "admin")).toLowerCase();
                if (!"admin".equals(role) && !"moderator".equals(role)) {
                    return Optional.empty();
                }
                return Optional.of(new UserRecord(
                        uname,
                        String.valueOf(user.getOrDefault("password_hash", "")),
                        role
                ));
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getUsers() {
        Optional<Object> raw = configManager.getValue("web.users");
        if (raw.isPresent() && raw.get() instanceof List<?> list) {
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> typed = new LinkedHashMap<>();
                    map.forEach((k, v) -> typed.put(String.valueOf(k), v));
                    result.add(typed);
                }
            }
            return result;
        }
        return new ArrayList<>();
    }

    private void updateUserPassword(String username, String newPassword) {
        List<Map<String, Object>> users = getUsers();
        String newHash = passwordHasher.hashPassword(newPassword);

        for (Map<String, Object> user : users) {
            if (username.equalsIgnoreCase(String.valueOf(user.get("username")))) {
                user.put("password_hash", newHash);
                break;
            }
        }

        configManager.setValue("web.users", users);
        configManager.save();
    }

    private String generateRandomPassword(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private static final class LoginState {
        private int failures;
        private long blockedUntilMs;
        private long lastSeenMs = System.currentTimeMillis();
    }

    private record UserRecord(String username, String passwordHash, String role) {
    }
}
