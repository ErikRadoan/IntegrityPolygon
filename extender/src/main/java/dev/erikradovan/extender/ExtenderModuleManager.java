package dev.erikradovan.extender;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erikradovan.extender.api.ExtenderModule;
import dev.erikradovan.extender.api.ExtenderModuleContext;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages dynamically loaded extender modules within the Paper extender plugin.
 *
 * <p>Modules are JAR files in {@code plugins/IntegrityPolygon-Extender/ext-modules/}
 * containing an {@code extender-module.json} descriptor and a class implementing
 * {@link ExtenderModule}.
 *
 * <p>Modules can be deployed remotely from the Velocity proxy via the TCP tunnel
 * (Base64-encoded JAR bytes in a {@code deploy_module} message).
 */
public class ExtenderModuleManager {

    private final JavaPlugin plugin;
    private final Path modulesDir;
    private final Path utilityDir;
    private final Logger logger;
    private final String extenderId;
    private final String serverLabel;
    private final BiConsumer<String, JsonObject> messageSender;
    private final Gson gson = new Gson();

    private final ConcurrentHashMap<String, LoadedModule> modules = new ConcurrentHashMap<>();

    public ExtenderModuleManager(JavaPlugin plugin, String extenderId, String serverLabel,
                                  BiConsumer<String, JsonObject> messageSender) {
        this.plugin = plugin;
        this.modulesDir = plugin.getDataFolder().toPath().resolve("ext-modules");
        this.utilityDir = plugin.getDataFolder().toPath().resolve("utility-classes");
        this.logger = plugin.getLogger();
        this.extenderId = extenderId;
        this.serverLabel = serverLabel;
        this.messageSender = messageSender;

        try {
            Files.createDirectories(modulesDir);
            Files.createDirectories(utilityDir);
        } catch (IOException e) {
            logger.warning("Could not create ext-modules directory: " + e.getMessage());
        }
    }

    /**
     * Scan the ext-modules directory and load all modules found.
     */
    public void loadAll() {
        loadFromDirectory(modulesDir, "ext-modules");
        loadRecursively(utilityDir, "utility-classes");
        logger.info("Loaded " + modules.size() + " extender module(s)");
    }

    private void loadFromDirectory(Path dir, String label) {
        File[] jars = dir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) {
            try {
                loadModule(jar.toPath());
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load extender module from " + label + ": " + jar.getName(), e);
            }
        }
    }

    private void loadRecursively(Path root, String label) {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar"))
                    .forEach(p -> {
                        try {
                            loadModule(p);
                        } catch (Exception e) {
                            logger.log(Level.WARNING, "Failed to load extender module from " + label + ": " + p.getFileName(), e);
                        }
                    });
        } catch (IOException e) {
            logger.log(Level.WARNING, "Failed scanning " + label + " modules", e);
        }
    }

    /**
     * Unload all modules (called on plugin disable).
     */
    public void unloadAll() {
        for (String id : modules.keySet()) {
            unloadModule(id);
        }
    }

    /**
     * Deploy a module from Base64-encoded JAR bytes received from the proxy.
     */
    public void deployModule(JsonObject payload) {
        String moduleId = payload.has("module_id") ? payload.get("module_id").getAsString() : "";
        String jarBase64 = payload.has("jar_data") ? payload.get("jar_data").getAsString() : "";
        String version = payload.has("version") ? payload.get("version").getAsString() : "?";

        if (moduleId.isEmpty() || jarBase64.isEmpty()) {
            logger.warning("Invalid deploy_module payload: missing module_id or jar_data");
            return;
        }

        try {
            byte[] jarBytes = Base64.getDecoder().decode(jarBase64);
            Path jarPath = resolveDeployPath(payload, moduleId);

            // Unload existing version if loaded
            if (modules.containsKey(moduleId)) {
                logger.info("Replacing extender module: " + moduleId);
                unloadModule(moduleId);
            }

            // Write JAR
            Files.write(jarPath, jarBytes);
            logger.info("Deployed extender module: " + moduleId + " v" + version +
                    " (" + jarBytes.length + " bytes)");

            // Load it
            loadModule(jarPath);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to deploy extender module: " + moduleId, e);
        }
    }

    /**
     * Undeploy a module by ID.
     */
    public void undeployModule(String moduleId) {
        undeployModule(moduleId, null);
    }

    public void undeployModule(String moduleId, JsonObject payload) {
        unloadModule(moduleId);
        try {
            Files.deleteIfExists(modulesDir.resolve(moduleId + ".jar"));
            if (payload != null && payload.has("deploy_path")) {
                String deployPath = payload.get("deploy_path").getAsString();
                if (deployPath != null && !deployPath.isBlank()) {
                    Path targetDir = utilityDir.resolve(deployPath.replace('\\', '/')).normalize();
                    if (targetDir.startsWith(utilityDir)) {
                        Files.deleteIfExists(targetDir.resolve(moduleId + ".jar"));
                    }
                }
            }
            try (var stream = Files.walk(utilityDir)) {
                stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().equalsIgnoreCase(moduleId + ".jar"))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
            logger.info("Undeployed extender module: " + moduleId);
        } catch (IOException e) {
            logger.warning("Could not delete extender module JAR: " + e.getMessage());
        }
    }

    private Path resolveDeployPath(JsonObject payload, String moduleId) throws IOException {
        if (payload.has("deploy_path")) {
            String deployPath = payload.get("deploy_path").getAsString();
            if (deployPath != null && !deployPath.isBlank()) {
                Path targetDir = utilityDir.resolve(deployPath.replace('\\', '/')).normalize();
                if (!targetDir.startsWith(utilityDir)) {
                    throw new IOException("Invalid deploy_path: " + deployPath);
                }
                Files.createDirectories(targetDir);
                return targetDir.resolve(moduleId + ".jar");
            }
        }
        return modulesDir.resolve(moduleId + ".jar");
    }

    /**
     * Route a message to the appropriate extender module.
     * Called when the extender receives a message with a module ID that matches a loaded module.
     *
     * @return true if the message was handled by a module
     */
    public boolean routeMessage(String moduleId, String type, JsonObject payload) {
        LoadedModule mod = modules.get(moduleId);
        if (mod == null) return false;

        // The module can handle messages via its own handler if it registered one
        if (mod.messageHandler != null) {
            try {
                mod.messageHandler.accept(type, payload);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Error in extender module message handler: " + moduleId, e);
            }
            return true;
        }
        return false;
    }

    /**
     * Check if a module with the given ID is loaded.
     */
    public boolean isLoaded(String moduleId) {
        return modules.containsKey(moduleId);
    }

    // ── Internal ──────────────────────────────────────────────────

    private void loadModule(Path jarPath) throws Exception {
        // Create classloader
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarPath.toUri().toURL()},
                plugin.getClass().getClassLoader()
        );

        // Read descriptor
        InputStream descStream = classLoader.getResourceAsStream("extender-module.json");
        if (descStream == null) {
            classLoader.close();
            throw new IllegalArgumentException("No extender-module.json in " + jarPath.getFileName());
        }

        String descJson = new String(descStream.readAllBytes(), StandardCharsets.UTF_8);
        descStream.close();
        JsonObject desc = JsonParser.parseString(descJson).getAsJsonObject();

        String id = desc.has("id") ? desc.get("id").getAsString() : "";
        String mainClass = desc.has("main") ? desc.get("main").getAsString() : "";
        String version = desc.has("version") ? desc.get("version").getAsString() : "?";

        if (id.isEmpty() || mainClass.isEmpty()) {
            classLoader.close();
            throw new IllegalArgumentException("Invalid extender-module.json: missing id or main");
        }

        // Instantiate
        Class<?> clazz = classLoader.loadClass(mainClass);
        if (!ExtenderModule.class.isAssignableFrom(clazz)) {
            classLoader.close();
            throw new IllegalArgumentException(mainClass + " does not implement ExtenderModule");
        }

        ExtenderModule moduleInstance = (ExtenderModule) clazz.getDeclaredConstructor().newInstance();

        // Create context
        Path dataDir = plugin.getDataFolder().toPath().resolve("ext-module-data").resolve(id);
        Files.createDirectories(dataDir);

        LoadedModule loaded = new LoadedModule(id, version, moduleInstance, classLoader, jarPath);

        ExtenderModuleContextImpl context = new ExtenderModuleContextImpl(
                plugin, id, dataDir,
                Logger.getLogger("IP-Ext-" + id),
                extenderId, serverLabel,
                (type, payload) -> {
                    // Send via the extender's TCP tunnel
                    JsonObject envelope = new JsonObject();
                    envelope.addProperty("module", id);
                    envelope.addProperty("type", type);
                    envelope.addProperty("source", extenderId);
                    envelope.addProperty("server_label", serverLabel);
                    envelope.add("payload", payload);
                    messageSender.accept(id, envelope);
                },
                loaded  // pass reference so module can register a message handler
        );

        moduleInstance.onEnable(context);
        modules.put(id, loaded);
        logger.info("Enabled extender module: " + id + " v" + version);
    }

    private void unloadModule(String moduleId) {
        LoadedModule mod = modules.remove(moduleId);
        if (mod == null) return;

        try {
            mod.instance.onDisable();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error disabling extender module: " + moduleId, e);
        }

        try {
            mod.classLoader.close();
        } catch (IOException e) {
            logger.warning("Could not close classloader for " + moduleId);
        }
        logger.info("Disabled extender module: " + moduleId);
    }

    // ── Data classes ──────────────────────────────────────────────

    static class LoadedModule {
        final String id;
        final String version;
        final ExtenderModule instance;
        final URLClassLoader classLoader;
        final Path jarPath;
        volatile BiConsumer<String, JsonObject> messageHandler;

        LoadedModule(String id, String version, ExtenderModule instance,
                     URLClassLoader classLoader, Path jarPath) {
            this.id = id;
            this.version = version;
            this.instance = instance;
            this.classLoader = classLoader;
            this.jarPath = jarPath;
        }
    }

    /**
     * Concrete implementation of ExtenderModuleContext.
     */
    static class ExtenderModuleContextImpl implements ExtenderModuleContext {
        private final JavaPlugin plugin;
        private final String moduleId;
        private final Path dataDirectory;
        private final Logger logger;
        private final String extenderId;
        private final String serverLabel;
        private final BiConsumer<String, JsonObject> sender;
        private final LoadedModule loadedModule;

        ExtenderModuleContextImpl(JavaPlugin plugin, String moduleId, Path dataDirectory,
                                   Logger logger, String extenderId, String serverLabel,
                                   BiConsumer<String, JsonObject> sender, LoadedModule loadedModule) {
            this.plugin = plugin;
            this.moduleId = moduleId;
            this.dataDirectory = dataDirectory;
            this.logger = logger;
            this.extenderId = extenderId;
            this.serverLabel = serverLabel;
            this.sender = sender;
            this.loadedModule = loadedModule;
        }

        @Override public JavaPlugin getPlugin() { return plugin; }
        @Override public String getModuleId() { return moduleId; }
        @Override public Path getDataDirectory() { return dataDirectory; }
        @Override public Logger getLogger() { return logger; }
        @Override public String getServerLabel() { return serverLabel; }
        @Override public String getExtenderId() { return extenderId; }

        @Override
        public void sendMessage(String type, JsonObject payload) {
            sender.accept(type, payload);
        }

        /**
         * Register a handler for incoming messages addressed to this module.
         * Called by extender modules that want to receive commands from the proxy.
         */
        @Override
        public void onMessage(BiConsumer<String, JsonObject> handler) {
            loadedModule.messageHandler = handler;
        }
    }
}

