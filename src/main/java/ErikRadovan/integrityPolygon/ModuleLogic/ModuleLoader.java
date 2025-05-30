package ErikRadovan.integrityPolygon.ModuleLogic;

import ErikRadovan.integrityPolygon.API.Module;
import ErikRadovan.integrityPolygon.API.ModuleContext;
import ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging.DebugInfoNode;
import ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging.LoadSessionLogger;
import ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging.LoadSessionLoggerImpl;
import ErikRadovan.integrityPolygon.Services.ApiRegistry;
import ErikRadovan.integrityPolygon.CheckSumLogic.ChecksumDatabase;
import ErikRadovan.integrityPolygon.Logging.LogCollector;
import ErikRadovan.integrityPolygon.Services.CrossProxyMessaging;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

public class ModuleLoader {

    private final File modulesDir;
    private final ChecksumDatabase checksumDatabase;
    private final ApiRegistry apiRegistry;
    private final Logger logger;
    private final ProxyServer proxy;
    private final Object pluginInstance;
    private final LogCollector logCollector;
    private final Map<String, Module> activeModules = new HashMap<>();
    private final Map<String, Long> jarTimestamps = new HashMap<>();
    private final File dataDirectory;
    private final CrossProxyMessaging proxyMessaging;

    private record ModuleMeta(File jar, String name, String mainClass, List<String> dependencies) {}

    private record ModuleLogEntry(String name, long loadTimeMs, boolean success, Exception exception) {}

    public ModuleLoader(File modulesDir, ChecksumDatabase checksumDatabase, ApiRegistry apiRegistry, Logger logger, ProxyServer proxy, Object pluginInstance, LogCollector logCollector, File dataDirectory, CrossProxyMessaging proxyMessaging) {
        this.modulesDir = modulesDir;
        this.checksumDatabase = checksumDatabase;
        this.apiRegistry = apiRegistry;
        this.logger = logger;
        this.proxy = proxy;
        this.pluginInstance = pluginInstance;
        this.logCollector = logCollector;
        this.dataDirectory = dataDirectory;
        this.proxyMessaging = proxyMessaging;
    }



    public void loadModules() {
        System.out.println("--------❯❯❯❯ Loading modules ❮❮❮❮--------");

        LoadSessionLogger loggerTree = new LoadSessionLoggerImpl();

        File[] jarFiles = modulesDir.listFiles((d, name) -> name.endsWith(".jar"));
        if (jarFiles == null) return;

        Map<String, ModuleMeta> moduleMap = new HashMap<>();
        Map<String, Exception> failedModules = new HashMap<>();

        for (File jar : jarFiles) {
            String name = jar.getName();
            try (JarFile jarFile = new JarFile(jar)) {

                /*
                if (!verifyChecksum(jar)) {
                    continue;
                }*/
                var entry = jarFile.getEntry("velocity-plugin.json");
                if (entry == null) {
                    failedModules.put(name, new RuntimeException("Missing velocity-plugin.json"));
                    continue;
                }

                InputStream in = jarFile.getInputStream(entry);
                String jsonText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(jsonText).getAsJsonObject();

                String mainClass = json.get("main").getAsString();
                List<String> dependencies = new ArrayList<>();
                if (json.has("dependencies")) {
                    JsonArray deps = json.getAsJsonArray("dependencies");
                    for (var dep : deps) {
                        JsonObject depObj = dep.getAsJsonObject();
                        if (depObj.has("id")) {
                            dependencies.add(depObj.get("id").getAsString());
                        }
                    }
                }

                moduleMap.put(name, new ModuleMeta(jar, name, mainClass, dependencies));

            } catch (Exception e) {
                failedModules.put(name, e);
            }
        }

        List<String> loadOrder = resolveLoadOrder(moduleMap);
        Set<String> loadedModules = new HashSet<>();

        for (String name : loadOrder) {
            DebugInfoNode category = loggerTree.createCategory(name);

            if (failedModules.containsKey(name)) {
                DebugInfoNode failedNode = category.addChild("Root", DebugInfoNode.LogLevel.FAILURE);
                failedNode.setException(failedModules.get(name));
                continue;
            }

            ModuleMeta meta = moduleMap.get(name);

            boolean depsOk = true;
            for (String dep : meta.dependencies) {
                if (!loadedModules.contains(dep)) {
                    DebugInfoNode failedNode = category.addChild("Missing dependency: " + dep, DebugInfoNode.LogLevel.FAILURE);
                    failedNode.setException(new RuntimeException("Missing dependency: " + dep));
                    depsOk = false;
                    break;
                }
            }

            if (!depsOk) continue;

            try {
                URLClassLoader loader = new URLClassLoader(new URL[]{meta.jar.toURI().toURL()}, getClass().getClassLoader());
                Class<?> clazz = Class.forName(meta.mainClass, true, loader);

                if (!Module.class.isAssignableFrom(clazz)) {
                    DebugInfoNode failedNode = category.addChild("Main class does not implement Module", DebugInfoNode.LogLevel.FAILURE);
                    failedNode.setException(new RuntimeException("Main class does not implement Module"));
                    continue;
                }

                Module module = (Module) clazz.getDeclaredConstructor().newInstance();
                ModuleContext context = new ModuleContextImpl(proxy, logger, apiRegistry, pluginInstance, logCollector, dataDirectory, proxyMessaging, loggerTree);
                module.load(context);

                category.addChild("Loaded", DebugInfoNode.LogLevel.SUCCESS);
                loadedModules.add(name);

            } catch (Exception e) {
                DebugInfoNode failedNode = category.addChild("Root", DebugInfoNode.LogLevel.FAILURE);
                failedNode.setException(e);
            }
        }

        loggerTree.printAll();
    }

    public void unloadModule(String name) {
        Module module = activeModules.remove(name);
        if (module != null) {
            try {
                module.unload();
                logger.info("➤  🔁  {} unloaded successfully", name);
            } catch (Exception e) {
                logger.info("➤  ❌  {} failed to unload", name, e);
            }
        }
    }

    private String computeSHA256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private File[] getJarFiles(File dir) {
        if (!modulesDir.exists()) modulesDir.mkdirs();
        return dir.listFiles((d, name) -> name.endsWith(".jar"));
    }

    private boolean verifyChecksum(File jar) throws Exception {
        String name = jar.getName();
        String actualChecksum = computeSHA256(jar);
        Optional<String> expectedChecksum = checksumDatabase.getChecksum(name);
        return expectedChecksum.map(checksum -> checksum.equalsIgnoreCase(actualChecksum)).orElse(false);
    }

    private List<String> resolveLoadOrder(Map<String, ModuleMeta> moduleMap) {
        List<String> sorted = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> visiting = new HashSet<>();

        for (String moduleName : moduleMap.keySet()) {
            if (!visited.contains(moduleName)) {
                if (dfsSort(moduleName, moduleMap, visited, visiting, sorted)) {
                    logger.error("Circular dependency detected involving module: {}", moduleName);
                }
            }
        }

        return sorted;
    }

    private boolean dfsSort(String current, Map<String, ModuleMeta> moduleMap, Set<String> visited, Set<String> visiting, List<String> sorted) {
        if (visiting.contains(current)) return true;
        if (visited.contains(current)) return false;

        visiting.add(current);
        ModuleMeta meta = moduleMap.get(current);
        if (meta != null) {
            for (String dep : meta.dependencies) {
                if (!moduleMap.containsKey(dep)) continue;
                if (dfsSort(dep, moduleMap, visited, visiting, sorted)) return true;
            }
        }

        visiting.remove(current);
        visited.add(current);
        sorted.add(current);
        return false;
    }
}