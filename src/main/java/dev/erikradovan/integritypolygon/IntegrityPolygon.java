package dev.erikradovan.integritypolygon;

import com.google.inject.Inject;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.api.ExtenderService;
import dev.erikradovan.integritypolygon.api.HttpService;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.ExtenderServiceImpl;
import dev.erikradovan.integritypolygon.core.HttpServiceImpl;
import dev.erikradovan.integritypolygon.core.ModuleManager;
import dev.erikradovan.integritypolygon.core.ModuleWatcher;
import dev.erikradovan.integritypolygon.core.ServiceRegistryImpl;
import dev.erikradovan.integritypolygon.core.SqliteModuleDatabase;
import dev.erikradovan.integritypolygon.logging.LogManager;
import dev.erikradovan.integritypolygon.messaging.ExtenderSocketServer;
import dev.erikradovan.integritypolygon.web.WebServer;
import dev.erikradovan.integritypolygon.web.auth.AuthManager;
import dev.erikradovan.integritypolygon.web.auth.SetupWizard;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Main Velocity plugin entry point for IntegrityPolygon.
 * <p>
 * Bootstraps the service registry, configuration, module manager,
 * embedded web server, and optional hot-reload watcher.
 */
@Plugin(
        id = "integritypolygon",
        name = "IntegrityPolygon",
        version = "2.0.0",
        description = "Modular server security management framework",
        authors = {"ErikRadovan"}
)
public class IntegrityPolygon {

    private final Logger logger;
    private final ProxyServer proxy;
    private final Path dataDirectory;

    private ConfigManager configManager;
    private ModuleManager moduleManager;
    private ModuleWatcher moduleWatcher;
    private WebServer webServer;
    private ExtenderSocketServer extenderSocketServer;
    private LogManager logManager;
    private SqliteModuleDatabase moduleDatabase;

    @Inject
    public IntegrityPolygon(Logger logger, ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // Ensure directory structure
        Path modulesJarDir = dataDirectory.resolve("modules");
        Path moduleDataDir = dataDirectory.resolve("module-data");
        createDirectories(modulesJarDir, moduleDataDir);

        // Initialize configuration
        configManager = new ConfigManager(dataDirectory);
        configManager.init();

        // Create service registry and register core services
        ServiceRegistryImpl serviceRegistry = new ServiceRegistryImpl();
        serviceRegistry.register(ProxyServer.class, proxy);
        serviceRegistry.register(ConfigManager.class, configManager);

        /*
        Logging manager will be used by a dedicated logging management module
        TODO:Create loggin management module
         */
        logManager = new LogManager();
        serviceRegistry.register(LogManager.class, logManager);

        // Initializes the TCP socket for the extender
        int extenderPort = configManager.getExtenderPort();
        String extenderSecret = configManager.getExtenderSecret();
        extenderSocketServer = new ExtenderSocketServer(extenderPort, extenderSecret, logger);
        extenderSocketServer.start();

        // Initialize Extender service (allows modules to deploy mini-modules to proxy connected paper servers)
        ExtenderServiceImpl extenderService = new ExtenderServiceImpl(
                extenderSocketServer, proxy, logger);
        serviceRegistry.register(ExtenderService.class, extenderService);

        // Register HTTP service available to all modules
        HttpServiceImpl httpService = new HttpServiceImpl();
        serviceRegistry.register(HttpService.class, httpService);

        // Shared SQLite database for module data and config
        moduleDatabase = new SqliteModuleDatabase(dataDirectory.resolve("integritypolygon.db"), logger);
        moduleDatabase.init();
        configManager.setModuleDatabase(moduleDatabase);
        serviceRegistry.register(SqliteModuleDatabase.class, moduleDatabase);

        // Initialize module manager
        moduleManager = new ModuleManager(
                modulesJarDir, moduleDataDir, serviceRegistry,
                proxy.getEventManager(), this, moduleDatabase, logger
        );

        // Attempt to generate new credentials if first launch (setupWizard handles first launch check)
        AuthManager authManager = new AuthManager(configManager);
        SetupWizard setupWizard = new SetupWizard(configManager, authManager);
        String generatedPassword = setupWizard.generateInitialCredentials();

        // Start embedded web server
        webServer = new WebServer(configManager, moduleManager, logManager, proxy, logger, dataDirectory);
        webServer.setExtenderService(extenderService);
        webServer.start();


        // If setup is complete, load modules and optionally start hot-reload watcher
        if (configManager.isSetupCompleted()) {
            if (configManager.isAutoUpdateOnRestartEnabled()) {
                runAutoUpdateOnRestart(modulesJarDir);
            }

            moduleManager.loadAll();

            if (configManager.isHotReloadEnabled()) {
                moduleWatcher = new ModuleWatcher(modulesJarDir, moduleManager, logger);
                Thread watcherThread = new Thread(moduleWatcher, "IP-ModuleWatcher");
                watcherThread.setDaemon(true);
                watcherThread.start();
            }
        }

        // Print startup banner
        int port = configManager.getWebPort();
        int tcpPort = configManager.getExtenderPort();
        String hostingDNS = configManager.getProxyHost();
        printStartupBanner(generatedPassword, hostingDNS, port, tcpPort);

        logManager.info("system", "startup", "IntegrityPolygon v2.0.0 initialized");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down IntegrityPolygon...");

        if (moduleWatcher != null) {
            moduleWatcher.stop();
        }
        if (moduleManager != null) {
            moduleManager.shutdown();
        }
        if (webServer != null) {
            webServer.stop();
        }
        if (extenderSocketServer != null) {
            extenderSocketServer.stop();
        }

        logger.info("IntegrityPolygon shut down.");
    }

    private void printStartupBanner(String generatedPassword, String hostingDNS, int port, int tcpPort) {
        logger.info("");
        logger.info("  ╔══════════════════════════════════════════╗");
        logger.info("  ║   IntegrityPolygon  ·  v2.0.0            ║");
        logger.info("  ║   Modular Security Framework             ║");
        logger.info("  ╚══════════════════════════════════════════╝");
        logger.info("");
        logger.info("  ▸ Panel : https://{}:{}", hostingDNS.equals("your.domain") ? "<your-domain>" : hostingDNS, port);
        logger.info("  ▸ Extender socket : port {}", tcpPort);

        // Show secret source — if no custom secret in config, we're using forwarding.secret
        boolean usingForwarding = configManager.<String>getValue("extender.secret")
                .map(String::isBlank).orElse(true);
        if (usingForwarding) {
            logger.info("  ▸ Extender auth : Velocity forwarding secret (auto)");
        } else {
            logger.info("  ▸ Extender auth : custom secret (see config.yml)");
        }

        if (generatedPassword != null) {
            logger.info("");
            logger.info("  ┌─────────────────────────────────────────┐");
            logger.info("  │  FIRST LAUNCH                           │");
            logger.info("  │  Username:  admin                       │");
            logger.info("  │  Password:  {}", String.format("%-28s│", generatedPassword));
            logger.info("  │                                         │");
            logger.info("  │  Log in and change your password,       │");
            logger.info("  │  then complete the setup wizard.        │");
            logger.info("  └─────────────────────────────────────────┘");
        } else if (!configManager.isSetupCompleted()) {
            logger.info("  ▸ Status: setup incomplete — open the panel to finish");
        } else {
            int moduleCount = 0;
            if (moduleManager != null) moduleCount = moduleManager.getLoadedModules().size();
            logger.info("  ▸ Status : ready");
            logger.info("  ▸ Modules: {} loaded", moduleCount);
        }

        logger.info("");
    }

    private void createDirectories(Path... dirs) {
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                logger.error("Failed to create directory: {}", dir, e);
            }
        }
    }

    private void runAutoUpdateOnRestart(Path modulesJarDir) {
        try {
            Map<String, String> installed = scanInstalledModuleVersions(modulesJarDir);
            if (installed.isEmpty()) return;

            String repositoryText = loadRepositoryIndexJson();
            if (repositoryText == null || repositoryText.isBlank()) {
                logger.warn("Auto-update enabled but repository index is unavailable");
                return;
            }

            JsonArray modules = extractModulesArray(repositoryText);
            String repositoryBase = deriveRepositoryBaseUrl(configManager.getRepositoryUrl());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            int updated = 0;

            for (JsonElement element : modules) {
                if (!element.isJsonObject()) continue;
                JsonObject mod = element.getAsJsonObject();
                String id = mod.has("id") ? mod.get("id").getAsString() : null;
                if (id == null || !installed.containsKey(id)) continue;

                String installedVersion = installed.get(id);
                String latestVersion = mod.has("version") ? mod.get("version").getAsString() : "unknown";
                if (!isUpdateAvailable(installedVersion, latestVersion)) continue;

                String downloadUrl = resolveDownloadUrl(mod, id, repositoryBase);
                if (downloadUrl.isBlank()) continue;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(downloadUrl))
                        .header("User-Agent", "IntegrityPolygon/2.0")
                        .timeout(Duration.ofSeconds(20))
                        .GET()
                        .build();

                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) continue;

                try (InputStream in = res.body()) {
                    Files.copy(in, modulesJarDir.resolve(id + ".jar"), StandardCopyOption.REPLACE_EXISTING);
                }
                updated++;
                logger.info("Auto-updated module '{}' from {} to {}", id, installedVersion, latestVersion);
            }

            if (updated > 0) {
                logger.info("Auto-update on restart applied {} module update(s)", updated);
            }
        } catch (Exception e) {
            logger.error("Failed during startup auto-update pass", e);
        }
    }

    private Map<String, String> scanInstalledModuleVersions(Path modulesJarDir) {
        Map<String, String> versions = new HashMap<>();
        java.io.File[] jars = modulesJarDir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null) return versions;

        for (java.io.File jar : jars) {
            try (JarFile jarFile = new JarFile(jar)) {
                var entry = jarFile.getEntry("module.json");
                if (entry == null) continue;

                try (InputStream in = jarFile.getInputStream(entry)) {
                    String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                    if (obj.has("id")) {
                        String id = obj.get("id").getAsString();
                        String version = obj.has("version") ? obj.get("version").getAsString() : "unknown";
                        versions.put(id, version);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return versions;
    }

    private String loadRepositoryIndexJson() {
        String configured = configManager.getRepositoryUrl();
        List<String> candidates = new ArrayList<>();
        if (configured != null && !configured.isBlank()) {
            candidates.add(configured);
        }
        candidates.add("https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");

        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        for (String url : candidates) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "IntegrityPolygon/2.0")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();
                HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) return res.body();
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private JsonArray extractModulesArray(String jsonText) {
        JsonElement root = JsonParser.parseString(jsonText);
        if (root.isJsonArray()) return root.getAsJsonArray();
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("modules") && obj.get("modules").isJsonArray()) return obj.getAsJsonArray("modules");
            if (obj.has("data") && obj.get("data").isJsonArray()) return obj.getAsJsonArray("data");
        }
        return new JsonArray();
    }

    private String deriveRepositoryBaseUrl(String repositoryUrl) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) return "";
        int slash = repositoryUrl.lastIndexOf('/');
        return slash < 0 ? repositoryUrl : repositoryUrl.substring(0, slash);
    }

    private String resolveDownloadUrl(JsonObject mod, String moduleId, String repositoryBase) {
        if (mod.has("download_url")) {
            String existing = mod.get("download_url").getAsString();
            if (existing != null && !existing.isBlank()) return existing;
        }
        if (repositoryBase == null || repositoryBase.isBlank()) return "";
        return repositoryBase + "/modules/" + moduleId + ".jar";
    }

    private boolean isUpdateAvailable(String installedVersion, String latestVersion) {
        if (installedVersion == null || latestVersion == null) return false;
        String installed = installedVersion.trim();
        String latest = latestVersion.trim();
        if (installed.isBlank() || latest.isBlank()) return false;
        if ("unknown".equalsIgnoreCase(installed) || "unknown".equalsIgnoreCase(latest)) {
            return !installed.equalsIgnoreCase(latest);
        }
        if (installed.equalsIgnoreCase(latest)) return false;

        String[] left = latest.replace('-', '.').replace('_', '.').split("\\.");
        String[] right = installed.replace('-', '.').replace('_', '.').split("\\.");
        int max = Math.max(left.length, right.length);
        for (int i = 0; i < max; i++) {
            String l = i < left.length ? left[i] : "0";
            String r = i < right.length ? right[i] : "0";
            if (l.chars().allMatch(Character::isDigit) && r.chars().allMatch(Character::isDigit)) {
                int cmp = Integer.compare(Integer.parseInt(l), Integer.parseInt(r));
                if (cmp != 0) return cmp > 0;
            } else {
                int cmp = l.compareToIgnoreCase(r);
                if (cmp != 0) return cmp > 0;
            }
        }

        return !latest.equalsIgnoreCase(installed);
    }
}
