package dev.erikradovan.integritypolygon.web.routes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import io.javalin.http.Context;

import java.util.Map;

/**
 * REST API routes for configuration management (main + per-module).
 */
public class ConfigRoutes {

    private final ConfigManager configManager;
    private final Gson gson = new Gson();

    public ConfigRoutes(ConfigManager configManager) {
        this.configManager = configManager;
    }

    /**
     * GET /api/config — returns the full main config.
     */
    public void getConfig(Context ctx) {
        ctx.json(configManager.getMainConfig());
    }

    /**
     * PUT /api/config — replaces the main config.
     */
    public void updateConfig(Context ctx) {
        Map<String, Object> newConfig = gson.fromJson(ctx.body(),
                new TypeToken<Map<String, Object>>() {}.getType());
        configManager.setMainConfig(newConfig);
        ctx.json(Map.of("success", true));
    }

    /**
     * PATCH /api/config/ports — update web panel port and/or extender TCP port.
     * Validates that ports are in valid range and not identical.
     * Changes take effect after a server restart.
     */
    public void updatePorts(Context ctx) {
        Map<String, Object> body = gson.fromJson(ctx.body(),
                new TypeToken<Map<String, Object>>() {}.getType());

        boolean changed = false;

        if (body.containsKey("web_port")) {
            int webPort = ((Number) body.get("web_port")).intValue();
            if (webPort < 1024 || webPort > 65535) {
                ctx.status(400).json(Map.of("error", "Web port must be between 1024 and 65535"));
                return;
            }
            configManager.setValue("web.port", webPort);
            changed = true;
        }

        if (body.containsKey("extender_port")) {
            int extPort = ((Number) body.get("extender_port")).intValue();
            if (extPort < 1024 || extPort > 65535) {
                ctx.status(400).json(Map.of("error", "Extender port must be between 1024 and 65535"));
                return;
            }
            configManager.setValue("extender.port", extPort);
            changed = true;
        }

        // Validate no collision
        int finalWebPort = configManager.getWebPort();
        int finalExtPort = configManager.getExtenderPort();
        if (finalWebPort == finalExtPort) {
            ctx.status(400).json(Map.of("error", "Web port and extender port cannot be the same"));
            return;
        }

        if (changed) {
            configManager.save();
        }

        ctx.json(Map.of(
                "success", true,
                "web_port", finalWebPort,
                "extender_port", finalExtPort,
                "restart_required", changed
        ));
    }

    /**
     * GET /api/config/modules/{id} — returns a module's config.
     */
    public void getModuleConfig(Context ctx) {
        String moduleId = ctx.pathParam("id");
        ctx.json(configManager.getModuleConfig(moduleId));
    }

    /**
     * PUT /api/config/modules/{id} — updates a module's config.
     */
    public void updateModuleConfig(Context ctx) {
        String moduleId = ctx.pathParam("id");
        Map<String, Object> config = gson.fromJson(ctx.body(),
                new TypeToken<Map<String, Object>>() {}.getType());
        configManager.saveModuleConfig(moduleId, config);
        ctx.json(Map.of("success", true));
    }
}

