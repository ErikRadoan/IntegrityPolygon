package ErikRadovan.integrityPolygon.Config;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Config {

    private static final String SESSION_CREDS_FILENAME = "session_creds";
    private static final Logger LOGGER = Logger.getLogger(Config.class.getName());
    private static Map<String, Object> configMap;

    private static File configFolder;

    // Static initializer block to set the config folder
    public static void init(File dataDirectory) {
        configFolder = dataDirectory;
        loadConfig();
    }

    // Load config method
    private static void loadConfig() {
        File configFile = new File(configFolder, "MainConfig.yml");
        try {
            // Check if config file exists
            if (!configFile.exists()) {
                LOGGER.info("Config file not found. Generating default config...");
                generateDefaultConfig(configFile);
            }

            Yaml yaml = new Yaml();
            try (InputStream inputStream = Files.newInputStream(configFile.toPath())) {
                configMap = yaml.load(inputStream);
                if (configMap == null) {
                    configMap = new HashMap<>();
                    LOGGER.warning("Config file is empty or invalid. Using empty config.");
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load config: " + e.getMessage(), e);
            configMap = new HashMap<>();
        }
    }

    // Generate default config method
    private static void generateDefaultConfig(File configFile) {
        try (InputStream defaultConfig = Config.class.getClassLoader().getResourceAsStream("MainConfig.yml")) {
            if (defaultConfig != null) {
                Files.copy(defaultConfig, configFile.toPath());
            } else {
                LOGGER.warning("Default config resource not found in plugin jar.");
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to generate default config: " + e.getMessage(), e);
        }
    }

    // Static dynamic getter with type safety
    @SuppressWarnings("unchecked")
    public static <T> Optional<T> getValue(Key key) {
        if (configMap == null) {
            LOGGER.warning("Config not loaded. Returning empty value.");
            return Optional.empty();
        }

        try {
            String[] path = key.getPath().split("\\.");
            Map<String, Object> currentMap = configMap;
            Object value = null;

            for (int i = 0; i < path.length; i++) {
                if (i == path.length - 1) {
                    value = currentMap.get(path[i]);
                } else {
                    Object nextMap = currentMap.get(path[i]);
                    if (nextMap instanceof Map) {
                        currentMap = (Map<String, Object>) nextMap;
                    } else {
                        return Optional.empty();
                    }
                }
            }

            return Optional.ofNullable((T) value);
        } catch (ClassCastException e) {
            LOGGER.log(Level.WARNING, "Config value type mismatch for key: " + key, e);
            return Optional.empty();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Invalid config key or type: " + key, e);
            return Optional.empty();
        }
    }

    // Reload method
    public static void reloadConfig(File configFile) {
        loadConfig();
    }

    // Config keys enum
    public enum Key {
        MODULE_WATCH("Module_Watch");

        private final String path;

        Key(String path) {
            this.path = path;
        }

        public String getPath() {
            return path;
        }
    }

    /**
     * Saves the user ID and public key to a file in the plugin's config folder.
     * The file is named 'session_creds' with no extension and is human-readable.
     *
     * @param userId       The user ID
     * @param publicKey    The public key
     */
    public static void saveSessionCredentials(String userId, String publicKey) {
        File file = new File(configFolder, SESSION_CREDS_FILENAME);
        try {
            List<String> lines = new ArrayList<>();
            lines.add("user_id=" + userId);
            lines.add("public_key=" + publicKey); // Already multiline string
            Files.write(file.toPath(), lines);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to save session credentials: " + e.getMessage(), e);
        }
    }


    /**
     * Loads the session credentials from the 'session_creds' file.
     *
     * @return Optional containing the credentials if the file is valid and exists
     */
    public static Optional<UserCredentials> loadSessionCredentials() {
        File file = new File(configFolder, SESSION_CREDS_FILENAME);
        if (!file.exists()) {
            LOGGER.warning("Session credentials file not found.");
            return Optional.empty();
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath());
            String userId = null;
            StringBuilder publicKeyBuilder = new StringBuilder();
            boolean inKey = false;

            for (String line : lines) {
                if (line.startsWith("user_id=")) {
                    userId = line.substring("user_id=".length());
                } else if (line.startsWith("public_key=")) {
                    publicKeyBuilder.append(line.substring("public_key=".length())).append("\n");
                    inKey = true;
                } else if (inKey) {
                    publicKeyBuilder.append(line).append("\n");
                    if (line.contains("END PUBLIC KEY")) {
                        inKey = false;  // End of key block
                    }
                }
            }

            String publicKey = publicKeyBuilder.toString().trim();

            if (userId != null && !publicKey.isEmpty()) {
                return Optional.of(new UserCredentials(userId, publicKey));
            } else {
                LOGGER.warning("Invalid format in session credentials file.");
                return Optional.empty();
            }

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to load session credentials: " + e.getMessage(), e);
            return Optional.empty();
        }
    }


    /**
         * Simple data holder for session credentials.
         */
        public record UserCredentials(String userId, String publicKey) {
    }
}