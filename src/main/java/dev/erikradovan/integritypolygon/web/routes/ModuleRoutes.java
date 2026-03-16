package dev.erikradovan.integritypolygon.web.routes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.LoadedModule;
import dev.erikradovan.integritypolygon.core.ModuleManager;
import io.javalin.http.Context;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;

/**
 * REST API routes for module management.
 */
public class ModuleRoutes {

    private final ModuleManager moduleManager;
    private final ConfigManager configManager;

    public ModuleRoutes(ModuleManager moduleManager, ConfigManager configManager) {
        this.moduleManager = moduleManager;
        this.configManager = configManager;
    }

    /** GET /api/modules — list all modules (loaded + disabled). */
    public void listModules(Context ctx) {
        Map<String, LoadedModule> loaded = moduleManager.getLoadedModules();
        Set<String> disabled = getDisabledModules();
        List<Map<String, Object>> result = new ArrayList<>();

        // Loaded modules
        for (var entry : loaded.entrySet()) {
            var desc = entry.getValue().descriptor();
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", desc.id());
            info.put("name", desc.name());
            info.put("version", desc.version());
            info.put("authors", desc.authors());
            info.put("description", desc.description());
            info.put("status", "loaded");
            info.put("enabled", true);
            result.add(info);
        }

        // Also scan JAR dir for unloaded modules — parse module.json to get real ID
        File[] jars = moduleManager.getModulesDir().toFile().listFiles(
                (d, name) -> name.endsWith(".jar"));
        if (jars != null) {
            Set<String> loadedIds = loaded.keySet();
            for (File jar : jars) {
                try {
                    // Parse the real module ID from the JAR's module.json
                    String realId = null;
                    String realName = null;
                    String realVersion = "?";
                    String realDesc = "";
                    List<String> realAuthors = List.of();
                    try (JarFile jarFile = new JarFile(jar)) {
                        var entry = jarFile.getEntry("module.json");
                        if (entry == null) continue; // Not a valid module JAR
                        try (InputStream in = jarFile.getInputStream(entry)) {
                            String jsonText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
                            realId = json.get("id").getAsString();
                            realName = json.has("name") ? json.get("name").getAsString() : realId;
                            realVersion = json.has("version") ? json.get("version").getAsString() : "?";
                            realDesc = json.has("description") ? json.get("description").getAsString() : "";
                            if (json.has("authors")) {
                                List<String> authors = new ArrayList<>();
                                json.getAsJsonArray("authors").forEach(el -> authors.add(el.getAsString()));
                                realAuthors = authors;
                            }
                        }
                    }

                    // Skip if already in the loaded list
                    if (realId == null || loadedIds.contains(realId)) continue;

                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("id", realId);
                    info.put("name", realName);
                    info.put("version", realVersion);
                    info.put("authors", realAuthors);
                    info.put("description", realDesc);
                    info.put("status", disabled.contains(realId) ? "disabled" : "unloaded");
                    info.put("enabled", !disabled.contains(realId));
                    result.add(info);
                } catch (Exception ignored) {
                    // Skip JARs that can't be parsed
                }
            }
        }

        ctx.json(result);
    }

    /** POST /api/modules/{id}/enable */
    public void enableModule(Context ctx) {
        String id = ctx.pathParam("id");
        Set<String> disabled = getDisabledModules();
        disabled.remove(id);
        saveDisabledModules(disabled);

        // Try to load immediately
        File jarFile = moduleManager.getModulesDir().resolve(id + ".jar").toFile();
        if (jarFile.exists() && !moduleManager.getLoadedModules().containsKey(id)) {
            try {
                moduleManager.loadModule(jarFile);
            } catch (Exception ignored) {}
        }
        ctx.json(Map.of("success", true, "message", id + " enabled"));
    }

    /** POST /api/modules/{id}/disable */
    public void disableModule(Context ctx) {
        String id = ctx.pathParam("id");
        Set<String> disabled = getDisabledModules();
        disabled.add(id);
        saveDisabledModules(disabled);

        // Unload if loaded
        if (moduleManager.getLoadedModules().containsKey(id)) {
            moduleManager.unloadModule(id);
        }
        ctx.json(Map.of("success", true, "message", id + " disabled"));
    }

    /** POST /api/modules/{id}/load */
    public void loadModule(Context ctx) {
        String id = ctx.pathParam("id");
        File[] jars = moduleManager.getModulesDir().toFile().listFiles(
                (d, name) -> name.endsWith(".jar"));

        if (jars != null) {
            for (File jar : jars) {
                try {
                    // Parse module.json to match by real module ID
                    String realId;
                    try (JarFile jarFile = new JarFile(jar)) {
                        var entry = jarFile.getEntry("module.json");
                        if (entry == null) continue;
                        try (InputStream in = jarFile.getInputStream(entry)) {
                            String jsonText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                            JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();
                            realId = json.get("id").getAsString();
                        }
                    }
                    if (realId.equalsIgnoreCase(id)) {
                        moduleManager.loadModule(jar);
                        ctx.json(Map.of("success", true, "message", id + " loaded"));
                        return;
                    }
                } catch (Exception e) {
                    // If parsing fails but filename matches, try loading anyway
                    if (jar.getName().replace(".jar", "").equalsIgnoreCase(id)) {
                        try {
                            moduleManager.loadModule(jar);
                            ctx.json(Map.of("success", true, "message", id + " loaded"));
                            return;
                        } catch (Exception ex) {
                            ctx.status(500).json(Map.of("error", "Failed to load: " + ex.getMessage()));
                            return;
                        }
                    }
                }
            }
        }
        ctx.status(404).json(Map.of("error", "Module JAR not found: " + id));
    }

    /** POST /api/modules/{id}/unload */
    public void unloadModule(Context ctx) {
        String id = ctx.pathParam("id");
        if (!moduleManager.getLoadedModules().containsKey(id)) {
            ctx.status(404).json(Map.of("error", "Module not loaded: " + id));
            return;
        }
        moduleManager.unloadModule(id);
        ctx.json(Map.of("success", true, "message", id + " unloaded"));
    }

    /** POST /api/modules/{id}/reload */
    public void reloadModule(Context ctx) {
        String id = ctx.pathParam("id");
        moduleManager.reloadModule(id);
        ctx.json(Map.of("success", true, "message", id + " reloaded"));
    }

    private Set<String> getDisabledModules() {
        Optional<Object> raw = configManager.getValue("modules.disabled");
        if (raw.isPresent() && raw.get() instanceof List<?> list) {
            Set<String> set = new LinkedHashSet<>();
            for (Object item : list) set.add(String.valueOf(item));
            return set;
        }
        return new LinkedHashSet<>();
    }

    private void saveDisabledModules(Set<String> disabled) {
        configManager.setValue("modules.disabled", new ArrayList<>(disabled));
        configManager.save();
    }
}
