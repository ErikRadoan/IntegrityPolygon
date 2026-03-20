package dev.erikradovan.integritypolygon.web.routes;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.SqliteModuleDatabase;
import io.javalin.http.Context;

import java.time.Instant;
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

    /** GET /api/config/db/export — exports module config schema/values snapshot JSON. */
    public void exportConfigDatabase(Context ctx) {
        SqliteModuleDatabase db = requireModuleDatabase();
        String payload = db.exportConfigSnapshotJson();
        String fileName = "integritypolygon-config-export-" + Instant.now().toEpochMilli() + ".json";
        ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        ctx.contentType("application/json");
        ctx.result(payload);
    }

    /** POST /api/config/db/import — imports module config schema/values snapshot JSON. */
    public void importConfigDatabase(Context ctx) {
        SqliteModuleDatabase db = requireModuleDatabase();
        db.importConfigSnapshotJson(ctx.body());
        ctx.json(Map.of("success", true));
    }

    /** GET /api/config/data/export — exports module data tables backup JSON. */
    public void exportDataBackup(Context ctx) {
        SqliteModuleDatabase db = requireModuleDatabase();
        String payload = db.exportDataBackupJson();
        String fileName = "integritypolygon-data-backup-" + Instant.now().toEpochMilli() + ".json";
        ctx.header("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
        ctx.contentType("application/json");
        ctx.result(payload);
    }

    /** POST /api/config/data/import — imports module data tables backup JSON. */
    public void importDataBackup(Context ctx) {
        SqliteModuleDatabase db = requireModuleDatabase();
        db.importDataBackupJson(ctx.body());
        ctx.json(Map.of("success", true));
    }

    private SqliteModuleDatabase requireModuleDatabase() {
        return configManager.getModuleDatabase()
                .orElseThrow(() -> new IllegalStateException("Module database is not initialized"));
    }
}

