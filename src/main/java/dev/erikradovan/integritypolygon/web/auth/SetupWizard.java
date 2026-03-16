package dev.erikradovan.integritypolygon.web.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import io.javalin.http.Context;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

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

    private final ConfigManager configManager;
    private final AuthManager authManager;
    private final Gson gson = new Gson();

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
        // If credentials already exist, don't regenerate
        if (configManager.<String>getValue("web.admin.password_hash").isPresent()) {
            return null;
        }

        String password = generateRandomPassword(12);
        String salt = generateSalt();
        String hash = hashPassword(password, salt);

        configManager.setValue("web.admin.username", "admin");
        configManager.setValue("web.admin.password_hash", hash);
        configManager.setValue("web.admin.salt", salt);
        configManager.setValue("web.admin.must_change_password", true);
        configManager.save();

        return password;
    }

    /**
     * POST /api/auth/login — authenticate and receive a JWT.
     * Expects JSON: {@code {"username":"...","password":"..."}}
     */
    public void handleLogin(Context ctx) {
        JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
        String username = body.has("username") ? body.get("username").getAsString() : "";
        String password = body.has("password") ? body.get("password").getAsString() : "";

        String storedUsername = configManager.<String>getValue("web.admin.username").orElse("");
        String storedHash = configManager.<String>getValue("web.admin.password_hash").orElse("");
        String storedSalt = configManager.<String>getValue("web.admin.salt").orElse("");

        if (!username.equals(storedUsername) || !hashPassword(password, storedSalt).equals(storedHash)) {
            ctx.status(401).json(Map.of("error", "Invalid credentials"));
            return;
        }

        String token = authManager.generateToken(username);

        boolean mustChangePassword = configManager.<Boolean>getValue("web.admin.must_change_password")
                .orElse(false);
        boolean setupCompleted = configManager.isSetupCompleted();

        ctx.json(Map.of(
                "token", token,
                "must_change_password", mustChangePassword,
                "setup_completed", setupCompleted
        ));
    }

    /**
     * POST /api/auth/change-password — change the admin password.
     * Expects JSON: {@code {"current_password":"...","new_password":"..."}}
     * Requires a valid JWT in the Authorization header.
     */
    public void handleChangePassword(Context ctx) {
        JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
        String currentPassword = body.has("current_password") ? body.get("current_password").getAsString() : "";
        String newPassword = body.has("new_password") ? body.get("new_password").getAsString() : "";

        if (newPassword.isBlank() || newPassword.length() < 8) {
            ctx.status(400).json(Map.of("error", "New password must be at least 8 characters"));
            return;
        }

        // Verify current password
        String storedHash = configManager.<String>getValue("web.admin.password_hash").orElse("");
        String storedSalt = configManager.<String>getValue("web.admin.salt").orElse("");

        if (!hashPassword(currentPassword, storedSalt).equals(storedHash)) {
            ctx.status(401).json(Map.of("error", "Current password is incorrect"));
            return;
        }

        // Set new password
        String newSalt = generateSalt();
        String newHash = hashPassword(newPassword, newSalt);

        configManager.setValue("web.admin.password_hash", newHash);
        configManager.setValue("web.admin.salt", newSalt);
        configManager.setValue("web.admin.must_change_password", false);
        configManager.save();

        // Generate a new token (old one still works but this one reflects the new state)
        String username = configManager.<String>getValue("web.admin.username").orElse("admin");
        String token = authManager.generateToken(username);

        ctx.json(Map.of("success", true, "token", token, "message", "Password changed successfully"));
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

        JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);

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
        boolean setupCompleted = configManager.isSetupCompleted();
        boolean mustChangePassword = configManager.<Boolean>getValue("web.admin.must_change_password").orElse(false);
        boolean hasCredentials = configManager.<String>getValue("web.admin.password_hash").isPresent();
        String extenderSecret = configManager.getExtenderSecret();
        int extenderPort = configManager.getExtenderPort();
        // If there's no custom secret in config, we're using the forwarding secret
        boolean usingForwardingSecret = configManager.<String>getValue("extender.secret")
                .map(String::isBlank).orElse(true);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("setup_completed", setupCompleted);
        result.put("has_credentials", hasCredentials);
        result.put("must_change_password", mustChangePassword);
        result.put("extender_secret", usingForwardingSecret ? "(using Velocity forwarding secret)" : extenderSecret);
        result.put("extender_secret_auto", usingForwardingSecret);
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
    }

    // ──────── Utilities ────────

    private String generateRandomPassword(int length) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789!@#$%";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    private String hashPassword(String password, String salt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Base64.getDecoder().decode(salt));
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash password", e);
        }
    }
}
