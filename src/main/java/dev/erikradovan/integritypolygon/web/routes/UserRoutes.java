package dev.erikradovan.integritypolygon.web.routes;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.web.auth.AuthManager;
import dev.erikradovan.integritypolygon.web.auth.PasswordHasher;
import io.javalin.http.Context;

import java.util.*;

/**
 * REST API routes for panel user management.
 * Users are stored in config under {@code web.users} as a list of maps.
 */
public class UserRoutes {

    private final ConfigManager configManager;
    private final AuthManager authManager;
    private final Gson gson = new Gson();
    private final PasswordHasher passwordHasher = new PasswordHasher();

    public UserRoutes(ConfigManager configManager, AuthManager authManager) {
        this.configManager = configManager;
        this.authManager = authManager;
    }

    /** GET /api/users — list all panel users (without password hashes). */
    public void listUsers(Context ctx) {
        List<Map<String, Object>> users = getUsers();
        List<Map<String, String>> safe = new ArrayList<>();
        for (Map<String, Object> u : users) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("username", String.valueOf(u.get("username")));
            entry.put("role", u.containsKey("role") ? String.valueOf(u.get("role")) : "admin");
            entry.put("created", u.containsKey("created") ? String.valueOf(u.get("created")) : "");
            safe.add(entry);
        }
        ctx.json(safe);
    }

    /** POST /api/users — add a new panel user. Body: {"username":"...","password":"...","role":"..."} */
    public void addUser(Context ctx) {
        JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
        String username = body.has("username") ? body.get("username").getAsString().trim() : "";
        String password = body.has("password") ? body.get("password").getAsString() : "";
        String role = body.has("role") ? body.get("role").getAsString().trim().toLowerCase() : "moderator";

        if (username.isBlank() || username.length() < 3) {
            ctx.status(400).json(Map.of("error", "Username must be at least 3 characters"));
            return;
        }
        if (password.isBlank() || password.length() < 8) {
            ctx.status(400).json(Map.of("error", "Password must be at least 8 characters"));
            return;
        }
        if (!Set.of("admin", "moderator").contains(role)) {
            ctx.status(400).json(Map.of("error", "Role must be admin or moderator"));
            return;
        }

        List<Map<String, Object>> users = getUsers();
        for (Map<String, Object> u : users) {
            if (username.equalsIgnoreCase(String.valueOf(u.get("username")))) {
                ctx.status(409).json(Map.of("error", "User already exists"));
                return;
            }
        }

        Map<String, Object> newUser = new LinkedHashMap<>();
        newUser.put("username", username);
        newUser.put("password_hash", passwordHasher.hashPassword(password));
        newUser.put("role", role);
        newUser.put("created", new Date().toString());

        users.add(newUser);
        configManager.setValue("web.users", users);
        configManager.save();

        ctx.json(Map.of("success", true, "message", "User '" + username + "' created"));
    }

    /** PUT /api/users/{username} — update a user's role or reset password. */
    public void updateUser(Context ctx) {
        String target = ctx.pathParam("username");
        JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
        List<Map<String, Object>> users = getUsers();

        for (Map<String, Object> u : users) {
            if (target.equalsIgnoreCase(String.valueOf(u.get("username")))) {
                if (body.has("role")) {
                    String newRole = body.get("role").getAsString().trim().toLowerCase();
                    if (!Set.of("admin", "moderator").contains(newRole)) {
                        ctx.status(400).json(Map.of("error", "Role must be admin or moderator"));
                        return;
                    }
                    u.put("role", newRole);
                    authManager.invalidateUserSessions(String.valueOf(u.get("username")));
                }
                if (body.has("password")) {
                    String newPassword = body.get("password").getAsString();
                    if (newPassword.length() < 8) {
                        ctx.status(400).json(Map.of("error", "Password must be at least 8 characters"));
                        return;
                    }
                    u.put("password_hash", passwordHasher.hashPassword(newPassword));
                    authManager.invalidateUserSessions(String.valueOf(u.get("username")));
                }
                configManager.setValue("web.users", users);
                configManager.save();
                ctx.json(Map.of("success", true, "message", "User '" + target + "' updated"));
                return;
            }
        }
        ctx.status(404).json(Map.of("error", "User not found"));
    }

    /** DELETE /api/users/{username} — remove a panel user. */
    public void removeUser(Context ctx) {
        String target = ctx.pathParam("username");

        // Don't allow deleting the last user
        List<Map<String, Object>> users = getUsers();
        if (users.size() <= 1) {
            ctx.status(400).json(Map.of("error", "Cannot delete the last user"));
            return;
        }

        boolean removed = users.removeIf(u -> target.equalsIgnoreCase(String.valueOf(u.get("username"))));
        if (!removed) {
            ctx.status(404).json(Map.of("error", "User not found"));
            return;
        }

        configManager.setValue("web.users", users);
        configManager.save();
        authManager.invalidateUserSessions(target);
        ctx.json(Map.of("success", true, "message", "User '" + target + "' removed"));
    }

    // â”€â”€ Helpers â”€â”€

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

    /**
     * Validate credentials against the users list.
     * @return the username if valid, null otherwise.
     */
    public String validateCredentials(String username, String password) {
        List<Map<String, Object>> users = getUsers();
        for (Map<String, Object> u : users) {
            String uname = String.valueOf(u.get("username"));
            String hash = String.valueOf(u.get("password_hash"));
            if (uname.equals(username) && passwordHasher.verify(password, hash)) {
                return uname;
            }
        }
        return null;
    }
}

