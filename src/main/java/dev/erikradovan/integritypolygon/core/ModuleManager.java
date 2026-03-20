package dev.erikradovan.integritypolygon.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.EventManager;
import dev.erikradovan.integritypolygon.api.Module;
import dev.erikradovan.integritypolygon.api.ModuleDescriptor;
import dev.erikradovan.integritypolygon.api.ServiceRegistry;
import dev.erikradovan.integritypolygon.web.websocket.RealtimeHandler;
import io.javalin.Javalin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.jar.JarFile;

/**
 * Manages the lifecycle of all modules: discovery, loading (with dependency
 * ordering), unloading, and reloading. Each module gets its own isolated
 * classloader, event manager, task scheduler, and dashboard.
 */
public class ModuleManager {

    private final Path modulesDir;
    private final Path modulesDataDir;
    private final Path dashboardDir;
    private final ServiceRegistry serviceRegistry;
    private final EventManager velocityEventManager;
    private final Object pluginInstance;
    private final Logger logger;
    private final SqliteModuleDatabase moduleDatabase;
    private final Map<String, LoadedModule> loadedModules = new ConcurrentHashMap<>();

    // Shared across all modules — daemon threads, configurable pool size
    private final ScheduledExecutorService sharedScheduler;

    // Set after WebServer is started
    private Javalin javalinApp;
    private RealtimeHandler realtimeHandler;

    public ModuleManager(Path modulesDir, Path modulesDataDir, ServiceRegistry serviceRegistry,
                         EventManager velocityEventManager, Object pluginInstance,
                         SqliteModuleDatabase moduleDatabase, Logger logger) {
        this.modulesDir = modulesDir;
        this.modulesDataDir = modulesDataDir;
        this.dashboardDir = modulesDataDir.getParent().resolve("dashboards");
        this.serviceRegistry = serviceRegistry;
        this.velocityEventManager = velocityEventManager;
        this.pluginInstance = pluginInstance;
        this.moduleDatabase = moduleDatabase;
        this.logger = logger;
        this.sharedScheduler = Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "IP-Scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Provide the web server references so modules can register dashboard routes.
     * Called after the WebServer is created but before modules are loaded.
     */
    public void setWebComponents(Javalin javalinApp, RealtimeHandler realtimeHandler) {
        this.javalinApp = javalinApp;
        this.realtimeHandler = realtimeHandler;
    }

    public void loadAll() {
        logger.info("──────── Loading modules ────────");
        File[] jarFiles = modulesDir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
        if (jarFiles == null || jarFiles.length == 0) {
            logger.info("No module JARs found in {}", modulesDir);
            return;
        }

        Map<String, DiscoveredModule> discovered = new LinkedHashMap<>();
        for (File jar : jarFiles) {
            try {
                ModuleDescriptor desc = parseDescriptor(jar);
                discovered.put(desc.id(), new DiscoveredModule(jar, desc));
            } catch (Exception e) {
                logger.error("Failed to parse module JAR: {}", jar.getName(), e);
            }
        }

        List<String> loadOrder = resolveLoadOrder(discovered);
        Set<String> loaded = new HashSet<>();

        for (String moduleId : loadOrder) {
            DiscoveredModule disc = discovered.get(moduleId);
            if (disc == null) continue;

            boolean depsOk = true;
            for (String dep : disc.descriptor.dependencies()) {
                if (!loaded.contains(dep) && !loadedModules.containsKey(dep)) {
                    logger.error("  ✗ {} — missing dependency: {}", moduleId, dep);
                    depsOk = false;
                    break;
                }
            }
            if (!depsOk) continue;

            try {
                loadModuleInternal(disc.jar, disc.descriptor);
                loaded.add(moduleId);
                logger.info("  ✓ {} v{} loaded{}", disc.descriptor.name(), disc.descriptor.version(),
                        disc.descriptor.hasDashboard() ? " [dashboard]" : "");
            } catch (Exception e) {
                logger.error("  ✗ {} failed to load", moduleId, e);
            }
        }
        logger.info("──────── {} modules loaded ────────", loaded.size());
    }

    private void loadModuleInternal(File jar, ModuleDescriptor descriptor) throws Exception {
        if (loadedModules.containsKey(descriptor.id())) {
            unloadModule(descriptor.id());
        }

        ModuleClassLoader classLoader = new ModuleClassLoader(jar.toURI().toURL(), getClass().getClassLoader());
        Class<?> clazz = Class.forName(descriptor.mainClass(), true, classLoader);

        if (!Module.class.isAssignableFrom(clazz)) {
            classLoader.close();
            throw new IllegalArgumentException("Main class " + descriptor.mainClass() + " does not implement Module");
        }

        Module module = (Module) clazz.getDeclaredConstructor().newInstance();
        Path moduleDataDir = modulesDataDir.resolve(descriptor.id());
        Files.createDirectories(moduleDataDir);

        Logger moduleLogger = LoggerFactory.getLogger("IntegrityPolygon:" + descriptor.id());

        // Per-module scoped services
        EventSubscriptionManagerImpl eventMgr = new EventSubscriptionManagerImpl(velocityEventManager, pluginInstance);
        TaskSchedulerImpl taskScheduler = new TaskSchedulerImpl(sharedScheduler, descriptor.id(), moduleLogger);

        // Dashboard (may be no-op if module has no dashboard or web server not ready)
        ModuleDashboardImpl dashboard = new ModuleDashboardImpl(
                descriptor.id(), javalinApp, realtimeHandler, dashboardDir, moduleLogger);

        if (descriptor.hasDashboard()) {
            dashboard.extractDashboardFiles(jar, descriptor.dashboardPath());
        }

        ModuleContextImpl context = new ModuleContextImpl(
                serviceRegistry, eventMgr, taskScheduler, dashboard,
                moduleLogger, moduleDataDir,
                moduleDatabase.createModuleStorage(descriptor.id()),
                moduleDatabase.createModuleConfigStore(descriptor.id()),
                descriptor);

        module.onEnable(context);
        loadedModules.put(descriptor.id(), new LoadedModule(module, descriptor, context, classLoader));
    }

    public void loadModule(File jar) throws Exception {
        ModuleDescriptor descriptor = parseDescriptor(jar);
        loadModuleInternal(jar, descriptor);
        logger.info("  ✓ {} v{} loaded", descriptor.name(), descriptor.version());
    }

    public void unloadModule(String moduleId) {
        LoadedModule loaded = loadedModules.remove(moduleId);
        if (loaded == null) {
            logger.warn("Module {} is not loaded", moduleId);
            return;
        }
        try {
            loaded.module().onDisable();
        } catch (Exception e) {
            logger.error("Error disabling module {}", moduleId, e);
        }
        // Clean up per-module resources
        loaded.context().getEventManager().unsubscribeAll();
        loaded.context().getTaskScheduler().cancelAll();
        if (loaded.context().getDashboard() instanceof ModuleDashboardImpl dashImpl) {
            dashImpl.cleanup();
        }
        try {
            loaded.classLoader().close();
        } catch (IOException e) {
            logger.error("Error closing classloader for {}", moduleId, e);
        }
        logger.info("  ↻ {} unloaded", moduleId);
    }

    public void reloadModule(String moduleId) {
        File[] jars = modulesDir.toFile().listFiles((d, name) -> name.endsWith(".jar"));
        if (jars == null) return;
        for (File jar : jars) {
            try {
                ModuleDescriptor desc = parseDescriptor(jar);
                if (desc.id().equals(moduleId)) {
                    loadModuleInternal(jar, desc);
                    logger.info("  ↻ {} reloaded", moduleId);
                    return;
                }
            } catch (Exception ignored) {
            }
        }
        logger.warn("Could not find JAR for module {} to reload", moduleId);
    }

    public void unloadAll() {
        List<String> ids = new ArrayList<>(loadedModules.keySet());
        Collections.reverse(ids);
        for (String id : ids) {
            unloadModule(id);
        }
    }

    public void shutdown() {
        unloadAll();
        sharedScheduler.shutdownNow();
    }

    public Map<String, LoadedModule> getLoadedModules() {
        return Collections.unmodifiableMap(loadedModules);
    }

    public Path getModulesDir() {
        return modulesDir;
    }

    private ModuleDescriptor parseDescriptor(File jar) throws Exception {
        try (JarFile jarFile = new JarFile(jar)) {
            var entry = jarFile.getEntry("module.json");
            if (entry == null) {
                throw new IllegalArgumentException("Missing module.json in " + jar.getName());
            }
            try (InputStream in = jarFile.getInputStream(entry)) {
                String jsonText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();

                String id = json.get("id").getAsString();
                String name = json.has("name") ? json.get("name").getAsString() : id;
                String version = json.has("version") ? json.get("version").getAsString() : "1.0.0";
                String mainClass = json.get("main").getAsString();
                String description = json.has("description") ? json.get("description").getAsString() : "";
                String dashboardPath = json.has("dashboard") ? json.get("dashboard").getAsString() : "";

                List<String> authors = new ArrayList<>();
                if (json.has("authors")) {
                    json.getAsJsonArray("authors").forEach(el -> authors.add(el.getAsString()));
                }

                List<String> dependencies = new ArrayList<>();
                if (json.has("dependencies")) {
                    json.getAsJsonArray("dependencies").forEach(el -> {
                        if (el.isJsonPrimitive()) {
                            dependencies.add(el.getAsString());
                        } else if (el.isJsonObject() && el.getAsJsonObject().has("id")) {
                            dependencies.add(el.getAsJsonObject().get("id").getAsString());
                        }
                    });
                }

                return new ModuleDescriptor(id, name, version, mainClass, authors, dependencies, description, dashboardPath);
            }
        }
    }

    private List<String> resolveLoadOrder(Map<String, DiscoveredModule> modules) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();
        for (String id : modules.keySet()) {
            if (!visited.contains(id) && topoSort(id, modules, visited, visiting, sorted)) {
                logger.error("Circular dependency detected involving module: {}", id);
            }
        }
        return sorted;
    }

    private boolean topoSort(String current, Map<String, DiscoveredModule> modules,
                             Set<String> visited, Set<String> visiting, List<String> sorted) {
        if (visiting.contains(current)) return true;
        if (visited.contains(current)) return false;
        visiting.add(current);
        DiscoveredModule disc = modules.get(current);
        if (disc != null) {
            for (String dep : disc.descriptor.dependencies()) {
                if (modules.containsKey(dep) && topoSort(dep, modules, visited, visiting, sorted)) return true;
            }
        }
        visiting.remove(current);
        visited.add(current);
        sorted.add(current);
        return false;
    }

    private record DiscoveredModule(File jar, ModuleDescriptor descriptor) {
    }
}

