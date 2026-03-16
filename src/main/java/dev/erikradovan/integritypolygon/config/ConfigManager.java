package dev.erikradovan.integritypolygon.config;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Manages YAML configuration for the main plugin and per-module configs.
 * Thread-safe for concurrent reads; writes are synchronized.
 */
public class ConfigManager {

    private final Path dataDirectory;
    private final Path mainConfigFile;
    private volatile Map<String, Object> mainConfig;
    private final Yaml yaml = new Yaml();

    public ConfigManager(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
        this.mainConfigFile = dataDirectory.resolve("config.yml");
    }

    /**
     * Initialize the config system. Loads or generates the main config file.
     */
    public void init() {
        try {
            Files.createDirectories(dataDirectory);

            if (!Files.exists(mainConfigFile)) {
                generateDefaultConfig();
            }

            reload();
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize configuration", e);
        }
    }

    /**
     * Reload the main config from disk.
     */
    public synchronized void reload() {
        try (InputStream in = Files.newInputStream(mainConfigFile)) {
            Map<String, Object> loaded = yaml.load(in);
            mainConfig = loaded != null ? loaded : new HashMap<>();
        } catch (IOException e) {
            mainConfig = new HashMap<>();
        }
    }

    /**
     * Get a value from the main config using a dot-separated path.
     * Example: getValue("web.port") navigates to the "web" map and returns "port".
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getValue(String path) {
        if (mainConfig == null) return Optional.empty();

        String[] parts = path.split("\\.");
        Map<String, Object> current = mainConfig;

        for (int i = 0; i < parts.length; i++) {
            Object value = current.get(parts[i]);
            if (i == parts.length - 1) {
                return Optional.ofNullable((T) value);
            }
            if (value instanceof Map) {
                current = (Map<String, Object>) value;
            } else {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * Set a value in the main config using a dot-separated path.
     */
    @SuppressWarnings("unchecked")
    public synchronized void setValue(String path, Object value) {
        if (mainConfig == null) mainConfig = new LinkedHashMap<>();

        String[] parts = path.split("\\.");
        Map<String, Object> current = mainConfig;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (next instanceof Map) {
                current = (Map<String, Object>) next;
            } else {
                Map<String, Object> newMap = new LinkedHashMap<>();
                current.put(parts[i], newMap);
                current = newMap;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    /**
     * Save the main config to disk.
     */
    public synchronized void save() {
        try (Writer writer = Files.newBufferedWriter(mainConfigFile)) {
            yaml.dump(mainConfig, writer);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config", e);
        }
    }

    /**
     * Get the full main config map (read-only snapshot).
     */
    public Map<String, Object> getMainConfig() {
        return mainConfig != null ? Collections.unmodifiableMap(mainConfig) : Collections.emptyMap();
    }

    /**
     * Replace the entire main config and save.
     */
    public synchronized void setMainConfig(Map<String, Object> config) {
        this.mainConfig = new LinkedHashMap<>(config);
        save();
    }

    // ──────── Per-module config ────────

    /**
     * Load a module's config file. Returns empty map if it doesn't exist.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getModuleConfig(String moduleId) {
        Path moduleConfigFile = dataDirectory.resolve("modules").resolve(moduleId).resolve("config.yml");
        if (!Files.exists(moduleConfigFile)) return new LinkedHashMap<>();

        try (InputStream in = Files.newInputStream(moduleConfigFile)) {
            Map<String, Object> loaded = yaml.load(in);
            return loaded != null ? loaded : new LinkedHashMap<>();
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Save a module's config.
     */
    public void saveModuleConfig(String moduleId, Map<String, Object> config) {
        Path moduleConfigDir = dataDirectory.resolve("modules").resolve(moduleId);
        try {
            Files.createDirectories(moduleConfigDir);
            Path moduleConfigFile = moduleConfigDir.resolve("config.yml");
            try (Writer writer = Files.newBufferedWriter(moduleConfigFile)) {
                yaml.dump(config, writer);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to save module config for " + moduleId, e);
        }
    }

    // ──────── Helpers ────────

    public boolean isSetupCompleted() {
        return getValue("setup.completed").map(v -> Boolean.TRUE.equals(v)).orElse(false);
    }

    public int getWebPort() {
        // Environment variable override — critical for first launch on hosted environments
        // where only a specific port is exposed (e.g., WEB_PORT=3490)
        String envPort = System.getenv("WEB_PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {
            }
        }
        return getValue("web.port").map(v -> {
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(v.toString());
        }).orElse(3490);
    }

    public String getWebBind() {
        String envBind = System.getenv("WEB_BIND");
        if (envBind != null && !envBind.isEmpty()) {
            return envBind;
        }
        return getValue("web.bind").map(Object::toString).orElse("0.0.0.0");
    }

    public int getExtenderPort() {
        // Environment variable override
        String envPort = System.getenv("EXTENDER_PORT");
        if (envPort != null && !envPort.isEmpty()) {
            try {
                return Integer.parseInt(envPort);
            } catch (NumberFormatException ignored) {
            }
        }
        return getValue("extender.port").map(v -> {
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(v.toString());
        }).orElse(getWebPort() + 1);
    }

    public boolean isHotReloadEnabled() {
        return getValue("modules.hot_reload").map(v -> Boolean.TRUE.equals(v)).orElse(true);
    }

    /**
     * Get the Velocity forwarding secret used for extender authentication.
     * <p>
     * This reuses the existing Velocity forwarding secret (from forwarding.secret)
     * so that Paper servers don't need a separate secret — they already have it
     * via paper-global.yml's {@code proxies.velocity.secret}.
     * <p>
     * Falls back to a generated extender-specific secret if the forwarding
     * secret file cannot be read.
     */
    public String getExtenderSecret() {
        // Try to read Velocity's forwarding.secret file.
        // We check two locations:
        //  1. Relative to plugin data dir: ../../forwarding.secret
        //  2. Current working directory: ./forwarding.secret (Velocity CWD is server root)
        String foundSecret = readForwardingSecret();
        if (foundSecret != null) {
            return foundSecret;
        }

        // Fallback: use a custom secret from config (for non-standard setups)
        Optional<String> existing = getValue("extender.secret");
        if (existing.isPresent() && !existing.get().isBlank()) {
            return existing.get();
        }
        // Generate a random secret and persist it
        String secret = java.util.UUID.randomUUID().toString().replace("-", "");
        setValue("extender.secret", secret);
        save();
        return secret;
    }

    public String getRepositoryUrl() {
        return getValue("modules.repository_url")
                .or(() -> getValue("extender.repository_url"))
                .map(Object::toString)
                .orElse("https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");
    }

    /**
     * Dynamically search for the Velocity forwarding.secret file.
     * <p>
     * Strategy:
     * <ol>
     *   <li>Check VELOCITY_FORWARDING_SECRET env var</li>
     *   <li>Walk UP from the plugin data directory checking each ancestor</li>
     *   <li>Check the current working directory</li>
     *   <li>Walk DOWN from the CWD (max depth 2) to find it</li>
     *   <li>Check relative to the plugin JAR location</li>
     * </ol>
     * Returns the secret string or null if not found.
     */
    private String readForwardingSecret() {
        // 0. Check env var
        try {
            String envSecret = System.getenv("VELOCITY_FORWARDING_SECRET");
            if (envSecret != null && !envSecret.isBlank()) return envSecret.trim();
        } catch (Exception ignored) {}

        // 1. Walk up from data directory
        try {
            Path dir = dataDirectory.toAbsolutePath().normalize();
            for (int i = 0; i < 5 && dir != null; i++) {
                dir = dir.getParent();
                if (dir == null) break;
                Path candidate = dir.resolve("forwarding.secret");
                String secret = tryReadSecret(candidate);
                if (secret != null) return secret;
            }
        } catch (Exception ignored) {}

        // 2. Check CWD
        try {
            Path cwd = Path.of("").toAbsolutePath();
            String secret = tryReadSecret(cwd.resolve("forwarding.secret"));
            if (secret != null) return secret;
        } catch (Exception ignored) {}

        // 3. Walk down from CWD (max depth 2) looking for the file
        try {
            Path cwd = Path.of("").toAbsolutePath();
            try (var stream = Files.find(cwd, 2,
                    (path, attrs) -> attrs.isRegularFile()
                            && path.getFileName().toString().equals("forwarding.secret"))) {
                var found = stream.findFirst();
                if (found.isPresent()) {
                    return tryReadSecret(found.get());
                }
            }
        } catch (Exception ignored) {}

        // 4. Try relative to the plugin JAR file location
        try {
            Path jarPath = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            Path jarDir = jarPath.getParent(); // plugins/
            Path serverRoot = jarDir != null ? jarDir.getParent() : null;
            if (serverRoot != null) {
                String secret = tryReadSecret(serverRoot.resolve("forwarding.secret"));
                if (secret != null) return secret;
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Try to read a secret from a file path. Returns the trimmed content or null.
     */
    private String tryReadSecret(Path path) {
        try {
            if (path != null && Files.exists(path) && Files.isRegularFile(path)) {
                byte[] bytes = Files.readAllBytes(path);
                int offset = 0;
                if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF
                        && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                    offset = 3;
                }
                String secret = new String(bytes, offset, bytes.length - offset,
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!secret.isBlank()) return secret;
            }
        } catch (Exception ignored) {}
        return null;
    }

    public Path getDataDirectory() {
        return dataDirectory;
    }

    public String getProxyHost() {
        return getValue("proxy.host").map(Object::toString).orElse("localhost");
    }

    public int getProxyPort() {
        return getValue("proxy.port").map(v -> {
            if (v instanceof Number) return ((Number) v).intValue();
            return Integer.parseInt(v.toString());
        }).orElse(25565);
    }

    public String getServerName() {
        return getValue("server.name").map(Object::toString).orElse("IntegrityPolygon Server");
    }

    private void generateDefaultConfig() throws IOException {
        try (InputStream defaultConfig = getClass().getClassLoader().getResourceAsStream("config.yml")) {
            if (defaultConfig != null) {
                Files.copy(defaultConfig, mainConfigFile);
            } else {
                // Generate a minimal default config programmatically
                Map<String, Object> defaults = new LinkedHashMap<>();

                Map<String, Object> web = new LinkedHashMap<>();
                web.put("port", 3490);
                web.put("bind", "0.0.0.0");
                defaults.put("web", web);

                Map<String, Object> setup = new LinkedHashMap<>();
                setup.put("completed", false);
                defaults.put("setup", setup);

                Map<String, Object> modules = new LinkedHashMap<>();
                modules.put("hot_reload", true);
                modules.put("repository_url", "https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");
                defaults.put("modules", modules);

                Map<String, Object> security = new LinkedHashMap<>();
                security.put("checksum_url", "https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/checksums.json");
                defaults.put("security", security);

                Map<String, Object> extender = new LinkedHashMap<>();
                extender.put("port", 3491);
                extender.put("secret", "");
                extender.put("repository_url", "https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Modules/main/modules.json");
                defaults.put("extender", extender);

                try (Writer writer = Files.newBufferedWriter(mainConfigFile)) {
                    yaml.dump(defaults, writer);
                }
            }
        }
    }
}

