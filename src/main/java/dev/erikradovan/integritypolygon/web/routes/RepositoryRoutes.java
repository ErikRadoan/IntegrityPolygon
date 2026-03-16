package dev.erikradovan.integritypolygon.web.routes;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
     * Build a map of module IDs to their installed versions by reading each JAR's module.json descriptor.
     * Falls back to filename-based matching if a JAR cannot be parsed.
     */
    private Map<String, String> scanInstalledModuleVersions() {
        Map<String, String> versions = new HashMap<>();
        java.io.File[] jars = moduleManager.getModulesDir().toFile().listFiles(
                (d, name) -> name.endsWith(".jar"));
        if (jars == null) return versions;

        for (java.io.File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                var entry = jarFile.getEntry("module.json");
                if (entry != null) {
                    try (InputStream in = jarFile.getInputStream(entry)) {
                        String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                        if (obj.has("id")) {
                            String id = obj.get("id").getAsString();
                            String ver = obj.has("version") ? obj.get("version").getAsString() : "unknown";
                            versions.put(id, ver);
                        }
                    }
                } else {
                    // Fallback: strip version-like suffixes and .jar from filename
                    String name = jar.getName().replace(".jar", "");
                    name = name.replaceAll("-\\d+\\.\\d+.*$", "");
                    versions.put(name, "unknown");
                }
            } catch (Exception e) {
                // Fallback to filename
                String name = jar.getName().replace(".jar", "");
                name = name.replaceAll("-\\d+\\.\\d+.*$", "");
                versions.put(name, "unknown");
            }
        }
        return versions;
    }

    private List<Map<String, Object>> parseRepositoryJson(String jsonText) {
        JsonArray modules = extractModulesArray(jsonText);
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> loadedIds = moduleManager.getLoadedModules().keySet();
        Map<String, String> installedVersions = scanInstalledModuleVersions();
        String repositoryBase = deriveRepositoryBaseUrl(configManager.getRepositoryUrl());

        for (var element : modules) {
            JsonObject mod = element.getAsJsonObject();
            String id = normalizeModuleId(mod);
            if (id == null) {
                continue;
            }

            String repoVersion = mod.has("version") ? mod.get("version").getAsString() : "unknown";
            boolean isInstalled = installedVersions.containsKey(id) || loadedIds.contains(id);
            String installedVer = installedVersions.getOrDefault(id, null);
            boolean updateAvailable = isInstalled && installedVer != null
                    && !"unknown".equals(installedVer) && !"unknown".equals(repoVersion)
                    && !repoVersion.equals(installedVer);

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", id);
            info.put("name", mod.has("name") ? mod.get("name").getAsString() : id);
            info.put("version", repoVersion);
            info.put("description", mod.has("description") ? mod.get("description").getAsString() : "");
            info.put("download_url", resolveDownloadUrl(mod, id, repositoryBase));
            info.put("image_url", mod.has("image_url") ? mod.get("image_url").getAsString() : "");
            info.put("author", mod.has("author") ? mod.get("author").getAsString() : "");
            info.put("installed", isInstalled);
            info.put("loaded", loadedIds.contains(id));
            if (installedVer != null) {
                info.put("installed_version", installedVer);
            }
            info.put("update_available", updateAvailable);
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
            JsonArray modules = extractModulesArray(repositoryText);
            String repositoryBase = deriveRepositoryBaseUrl(configManager.getRepositoryUrl());

            String downloadUrl = null;
            for (var element : modules) {
                JsonObject mod = element.getAsJsonObject();
                String id = normalizeModuleId(mod);
                if (id != null && id.equals(moduleId)) {
                    downloadUrl = resolveDownloadUrl(mod, id, repositoryBase);
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

    /** POST /api/repository/{id}/uninstall */
    public void uninstallModule(Context ctx) {
        String moduleId = URLDecoder.decode(ctx.pathParam("id"), StandardCharsets.UTF_8);
        if (!MODULE_ID_PATTERN.matcher(moduleId).matches()) {
            ctx.status(400).json(Map.of("error", "Invalid module id: " + moduleId));
            return;
        }

        try {
            // First try to unload from ModuleManager
            moduleManager.unloadModule(moduleId);

            // Then delete JAR from disk
            java.io.File modulesDir = moduleManager.getModulesDir().toFile();
            java.io.File[] jarFiles = modulesDir.listFiles((d, name) -> 
                name.toLowerCase().startsWith(moduleId.toLowerCase()) && name.endsWith(".jar"));
            
            if (jarFiles != null) {
                for (java.io.File jarFile : jarFiles) {
                    try (JarFile jf = new JarFile(jarFile)) {
                        var entry = jf.getEntry("module.json");
                        if (entry != null) {
                            try (InputStream in = jf.getInputStream(entry)) {
                                String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                                if (obj.has("id") && obj.get("id").getAsString().equals(moduleId)) {
                                    Files.deleteIfExists(jarFile.toPath());
                                    ctx.json(Map.of("success", true, "message", moduleId + " uninstalled"));
                                    return;
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // Try fallback filename match
                        String name = jarFile.getName().replaceAll("-\\d+\\.\\d+.*\\.jar$", "");
                        if (name.equals(moduleId)) {
                            Files.deleteIfExists(jarFile.toPath());
                            ctx.json(Map.of("success", true, "message", moduleId + " uninstalled"));
                            return;
                        }
                    }
                }
            }

            ctx.status(404).json(Map.of("error", "Module not found on disk: " + moduleId));
        } catch (Exception e) {
            logger.error("Failed to uninstall module {}", moduleId, e);
            ctx.status(500).json(Map.of("error", "Uninstallation failed: " + e.getMessage()));
        }
    }

    private String loadRepositoryIndexJson() {
        String repoUrl = configManager.getRepositoryUrl();

        List<String> candidates = new ArrayList<>();
        if (repoUrl != null && !repoUrl.isBlank()) {
            candidates.add(repoUrl);
            String migrated = migrateLegacyRepoUrl(repoUrl);
            if (migrated != null && !migrated.equals(repoUrl)) {
                candidates.add(migrated);
            }
        }
        // Final hard fallback to the dedicated public modules repo index.
        if (!candidates.contains("https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json")) {
            candidates.add("https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");
        }

        for (String url : candidates) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "IntegrityPolygon/2.0")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    if (!url.equals(repoUrl)) {
                        logger.info("Repository index migrated from {} to {}", repoUrl, url);
                    }
                    return response.body();
                }
                logger.warn("Repository index HTTP {} for {}", response.statusCode(), url);
            } catch (IOException | InterruptedException e) {
                logger.warn("Repository fetch failed for {}: {}", url, e.getMessage());
            }
        }

        logger.warn("Repository index unavailable from all remote candidates — trying local fallback");

        return loadLocalRepository();
    }

    private String migrateLegacyRepoUrl(String url) {
        String migrated = url;

        migrated = migrated.replace(
                "raw.githubusercontent.com/ErikRadoan/IntegrityPolygon/main/repo/modules.json",
                "raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");
        migrated = migrated.replace(
                "raw.githubusercontent.com/ErikRadoan/IntegrityPolygon/main/modules.json",
                "raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");

        return migrated;
    }

    private String normalizeModuleId(JsonObject mod) {
        String raw = null;
        if (mod.has("id")) {
            raw = mod.get("id").getAsString();
        } else if (mod.has("module_id")) {
            raw = mod.get("module_id").getAsString();
        } else if (mod.has("artifact_id")) {
            raw = mod.get("artifact_id").getAsString();
        } else if (mod.has("artifactId")) {
            raw = mod.get("artifactId").getAsString();
        }

        if (raw == null || raw.isBlank()) return null;

        raw = raw.trim().toLowerCase(Locale.ROOT);
        if (raw.contains(":")) {
            String[] parts = raw.split(":");
            if (parts.length >= 2 && !parts[1].isBlank()) {
                raw = parts[1];
            } else {
                return null;
            }
        }

        if (!MODULE_ID_PATTERN.matcher(raw).matches()) {
            return null;
        }
        return raw;
    }

    private JsonArray extractModulesArray(String jsonText) {
        JsonElement root = JsonParser.parseString(jsonText);
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("modules") && obj.get("modules").isJsonArray()) {
                return obj.getAsJsonArray("modules");
            }
            if (obj.has("data") && obj.get("data").isJsonArray()) {
                return obj.getAsJsonArray("data");
            }
        }
        return new JsonArray();
    }

    private String deriveRepositoryBaseUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            return "";
        }
        int slash = repositoryUrl.lastIndexOf('/');
        if (slash < 0) {
            return repositoryUrl;
        }
        return repositoryUrl.substring(0, slash);
    }

    private String resolveDownloadUrl(JsonObject mod, String moduleId, String repositoryBase) {
        if (mod.has("download_url")) {
            String existing = mod.get("download_url").getAsString();
            if (existing != null && !existing.isBlank()) {
                if (existing.contains("raw.githubusercontent.com/ErikRadoan/IntegrityPolygon/main/modules/")) {
                    return existing.replace(
                            "raw.githubusercontent.com/ErikRadoan/IntegrityPolygon/main/modules/",
                            "raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules/");
                }
                if (existing.contains("/main/modules/")) {
                    return existing;
                }
                if (existing.contains("/main/repo/modules/")) {
                    return existing.replace("/main/repo/modules/", "/main/modules/");
                }
                return existing;
            }
        }
        if (repositoryBase == null || repositoryBase.isBlank()) {
            return "";
        }

        return repositoryBase + "/modules/" + moduleId + ".jar";
    }
}
