package dev.erikradovan.integritypolygon.web.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.ModuleManager;
import io.javalin.http.Context;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import java.util.jar.JarFile;

/**
 * REST API routes for the module repository (browse and install modules from remote).
 */
public class RepositoryRoutes {

    private static final Pattern MODULE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    private final ConfigManager configManager;
    private final ModuleManager moduleManager;
    private final Logger logger;
    private final HttpClient httpClient;

    public RepositoryRoutes(ConfigManager configManager, ModuleManager moduleManager, Logger logger) {
        this.configManager = configManager;
        this.moduleManager = moduleManager;
        this.logger = logger;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /** GET /api/repository */
    public void listAvailable(Context ctx) {
        try {
            String jsonText = loadRepositoryIndexJson();
            if (jsonText == null) {
                ctx.status(502).json(Map.of("error", "Failed to fetch repository index"));
                return;
            }
            ctx.json(parseRepositoryJson(jsonText));
        } catch (Exception e) {
            logger.error("Repository fetch error", e);
            ctx.status(500).json(Map.of("error", "Repository error: " + e.getMessage()));
        }
    }

    /**
     * Build a set of module IDs that are present on disk by reading each JAR's module.json descriptor.
     * Falls back to filename-based matching if a JAR cannot be parsed.
     */
    private Set<String> scanInstalledModuleIds() {
        Set<String> ids = new HashSet<>();
        java.io.File[] jars = moduleManager.getModulesDir().toFile().listFiles(
                (d, name) -> name.endsWith(".jar"));
        if (jars == null) return ids;

        for (java.io.File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                var entry = jarFile.getEntry("module.json");
                if (entry != null) {
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        if (obj.has("id")) {
                            ids.add(obj.get("id").getAsString());
                        }
                    }
                } else {
                    // Fallback: strip version-like suffixes and .jar from filename
                    String name = jar.getName().replace(".jar", "");
                    // Remove trailing version patterns like -1.0.0, -2.0.0-SNAPSHOT
                    name = name.replaceAll("-\\d+\\.\\d+.*$", "");
                    ids.add(name);
                }
            } catch (Exception e) {
                // Fallback to filename
                String name = jar.getName().replace(".jar", "");
                name = name.replaceAll("-\\d+\\.\\d+.*$", "");
                ids.add(name);
            }
        }
        return ids;
    }

    private List<Map<String, Object>> parseRepositoryJson(String jsonText) {
        JsonArray modules = JsonParser.parseString(jsonText).getAsJsonArray();
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> loadedIds = moduleManager.getLoadedModules().keySet();
        Set<String> onDisk = scanInstalledModuleIds();

        for (var element : modules) {
            JsonObject mod = element.getAsJsonObject();
            String id = normalizeModuleId(mod);
            if (id == null) {
                continue;
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", id);
            info.put("name", mod.has("name") ? mod.get("name").getAsString() : id);
            info.put("version", mod.has("version") ? mod.get("version").getAsString() : "unknown");
            info.put("description", mod.has("description") ? mod.get("description").getAsString() : "");
            info.put("download_url", mod.has("download_url") ? mod.get("download_url").getAsString() : "");
            info.put("image_url", mod.has("image_url") ? mod.get("image_url").getAsString() : "");
            info.put("author", mod.has("author") ? mod.get("author").getAsString() : "");
            info.put("installed", onDisk.contains(id) || loadedIds.contains(id));
            info.put("loaded", loadedIds.contains(id));
            result.add(info);
        }

        return result;
    }

    /**
     * Try to load a local modules.json from the plugin's data directory.
     * Returns the JSON text or null if not found.
     */
    private String loadLocalRepository() {
        Path localFile = configManager.getDataDirectory().resolve("repo").resolve("modules.json");
        if (Files.exists(localFile)) {
            try {
                return Files.readString(localFile);
            } catch (IOException e) {
                logger.warn("Failed to read local repo: {}", e.getMessage());
            }
        }
        return null;
    }

    /** POST /api/repository/{id}/install */
    public void installModule(Context ctx) {
        String moduleId = URLDecoder.decode(ctx.pathParam("id"), StandardCharsets.UTF_8);
        if (!MODULE_ID_PATTERN.matcher(moduleId).matches()) {
            ctx.status(400).json(Map.of("error", "Invalid module id: " + moduleId));
            return;
        }

        try {
            String repositoryText = loadRepositoryIndexJson();
            if (repositoryText == null) {
                ctx.status(502).json(Map.of("error", "Module repository is unreachable"));
                return;
            }
            JsonArray modules = JsonParser.parseString(repositoryText).getAsJsonArray();

            String downloadUrl = null;
            for (var element : modules) {
                JsonObject mod = element.getAsJsonObject();
                String id = normalizeModuleId(mod);
                if (id != null && id.equals(moduleId) && mod.has("download_url")) {
                    downloadUrl = mod.get("download_url").getAsString();
                    break;
                }
            }

            if (downloadUrl == null) {
                ctx.status(404).json(Map.of("error", "Module not found in repository: " + moduleId));
                return;
            }

            HttpRequest downloadRequest = HttpRequest.newBuilder()
                    .uri(URI.create(downloadUrl))
                    .header("User-Agent", "IntegrityPolygon/2.0")
                    .GET()
                    .build();

            HttpResponse<InputStream> downloadResponse = httpClient.send(downloadRequest,
                    HttpResponse.BodyHandlers.ofInputStream());

            if (downloadResponse.statusCode() != 200) {
                ctx.status(502).json(Map.of("error", "Failed to download module. HTTP " + downloadResponse.statusCode()));
                return;
            }

            Path targetPath = moduleManager.getModulesDir().resolve(moduleId + ".jar");
            try (InputStream body = downloadResponse.body()) {
                Files.copy(body, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }

            moduleManager.loadModule(targetPath.toFile());
            ctx.json(Map.of("success", true, "message", moduleId + " installed and loaded"));
        } catch (Exception e) {
            logger.error("Failed to install module {}", moduleId, e);
            ctx.status(500).json(Map.of("error", "Installation failed: " + e.getMessage()));
        }
    }

    private String loadRepositoryIndexJson() {
        String repoUrl = configManager.getRepositoryUrl();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(repoUrl))
                    .header("User-Agent", "IntegrityPolygon/2.0")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return response.body();
            }
            logger.warn("Repository index HTTP {} — trying local fallback", response.statusCode());
        } catch (IOException | InterruptedException e) {
            logger.warn("Repository fetch failed: {} — trying local fallback", e.getMessage());
        }

        return loadLocalRepository();
    }

    private String normalizeModuleId(JsonObject mod) {
        if (!mod.has("id")) return null;
        String raw = mod.get("id").getAsString().trim().toLowerCase(Locale.ROOT);
        if (raw.contains(":")) {
            return null;
        }
        if (!MODULE_ID_PATTERN.matcher(raw).matches()) {
            return null;
        }
        return raw;
    }
}
