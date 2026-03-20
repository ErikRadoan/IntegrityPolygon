package dev.erikradovan.integritypolygon.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erikradovan.integritypolygon.api.ModuleConfigStore;
import dev.erikradovan.integritypolygon.api.ModuleStorage;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.sqlite.JDBC;

/**
 * Shared SQLite database used by all modules for structured data and config.
 */
public class SqliteModuleDatabase {

    private static final Pattern MODULE_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");

    private final Path dbPath;
    private final Logger logger;
    private final Gson gson = new Gson();

    public SqliteModuleDatabase(Path dbPath, Logger logger) {
        this.dbPath = dbPath;
        this.logger = logger;
    }

    public void init() {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            try (Connection conn = openConnection();
                 Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");

                st.execute("""
                        CREATE TABLE IF NOT EXISTS module_config_schema (
                          module_id TEXT NOT NULL,
                          config_key TEXT NOT NULL,
                          type TEXT NOT NULL,
                          nullable INTEGER NOT NULL DEFAULT 0,
                          description TEXT,
                          default_json TEXT,
                          allowed_values_json TEXT,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(module_id, config_key)
                        )
                        """);

                st.execute("""
                        CREATE TABLE IF NOT EXISTS module_config_values (
                          module_id TEXT NOT NULL,
                          config_key TEXT NOT NULL,
                          value_json TEXT,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(module_id, config_key),
                          FOREIGN KEY(module_id, config_key)
                            REFERENCES module_config_schema(module_id, config_key)
                            ON DELETE CASCADE
                        )
                        """);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize module SQLite database: " + dbPath, e);
        }
    }

    public Connection openConnection() throws Exception {
        ensureDriverLoaded();
        return DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().normalize());
    }

    private void ensureDriverLoaded() throws SQLException {
        try {
            // Direct class reference prevents shading/minimization from stripping the JDBC driver.
            DriverManager.registerDriver(new JDBC());
        } catch (Exception e) {
            throw new SQLException("SQLite JDBC driver is not available in plugin runtime", e);
        }
    }

    public ModuleStorage createModuleStorage(String moduleId) {
        validateModuleId(moduleId);
        return new ModuleStorageImpl(this, moduleId);
    }

    public ModuleConfigStore createModuleConfigStore(String moduleId) {
        validateModuleId(moduleId);
        return new ModuleConfigStoreImpl(this, gson, moduleId);
    }

    public Map<String, Object> getModuleConfigMap(String moduleId) {
        return createModuleConfigStore(moduleId).getAll();
    }

    public void saveModuleConfigMap(String moduleId, Map<String, Object> values) {
        createModuleConfigStore(moduleId).setAll(values);
    }

    public void validateModuleId(String moduleId) {
        if (moduleId == null || !MODULE_ID_PATTERN.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("Invalid module id: " + moduleId);
        }
    }

    public String exportConfigSnapshotJson() {
        try (Connection conn = openConnection()) {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("exported_at", Instant.now().toEpochMilli());
            root.add("schema", queryRowsAsJson(conn,
                    "SELECT module_id, config_key, type, nullable, description, default_json, allowed_values_json, updated_at FROM module_config_schema ORDER BY module_id, config_key"));
            root.add("values", queryRowsAsJson(conn,
                    "SELECT module_id, config_key, value_json, updated_at FROM module_config_values ORDER BY module_id, config_key"));
            return gson.toJson(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export config snapshot", e);
        }
    }

    public void importConfigSnapshotJson(String jsonText) {
        Objects.requireNonNull(jsonText, "jsonText");
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        JsonArray schemaRows = root.has("schema") && root.get("schema").isJsonArray() ? root.getAsJsonArray("schema") : new JsonArray();
        JsonArray valueRows = root.has("values") && root.get("values").isJsonArray() ? root.getAsJsonArray("values") : new JsonArray();

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.executeUpdate("DELETE FROM module_config_values");
                    st.executeUpdate("DELETE FROM module_config_schema");
                }

                String schemaSql = """
                        INSERT INTO module_config_schema
                        (module_id, config_key, type, nullable, description, default_json, allowed_values_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(schemaSql)) {
                    for (JsonElement rowEl : schemaRows) {
                        if (!rowEl.isJsonObject()) continue;
                        JsonObject row = rowEl.getAsJsonObject();
                        ps.setString(1, str(row, "module_id"));
                        ps.setString(2, str(row, "config_key"));
                        ps.setString(3, str(row, "type"));
                        ps.setInt(4, intVal(row, "nullable", 0));
                        ps.setString(5, nullableStr(row, "description"));
                        ps.setString(6, nullableStr(row, "default_json"));
                        ps.setString(7, nullableStr(row, "allowed_values_json"));
                        ps.setLong(8, longVal(row, "updated_at", Instant.now().toEpochMilli()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                String valueSql = """
                        INSERT INTO module_config_values
                        (module_id, config_key, value_json, updated_at)
                        VALUES (?, ?, ?, ?)
                        """;
                try (PreparedStatement ps = conn.prepareStatement(valueSql)) {
                    for (JsonElement rowEl : valueRows) {
                        if (!rowEl.isJsonObject()) continue;
                        JsonObject row = rowEl.getAsJsonObject();
                        ps.setString(1, str(row, "module_id"));
                        ps.setString(2, str(row, "config_key"));
                        ps.setString(3, nullableStr(row, "value_json"));
                        ps.setLong(4, longVal(row, "updated_at", Instant.now().toEpochMilli()));
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to import config snapshot", e);
        }
    }

    public String exportDataBackupJson() {
        try (Connection conn = openConnection()) {
            JsonObject root = new JsonObject();
            root.addProperty("version", 1);
            root.addProperty("exported_at", Instant.now().toEpochMilli());

            JsonArray tables = new JsonArray();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('module_config_schema','module_config_values') ORDER BY name")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("name");
                        String createSql = rs.getString("sql");

                        JsonObject table = new JsonObject();
                        table.addProperty("name", tableName);
                        table.addProperty("create_sql", createSql);
                        table.add("rows", queryRowsAsJson(conn, "SELECT * FROM " + tableName));
                        tables.add(table);
                    }
                }
            }
            root.add("tables", tables);
            return gson.toJson(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to export data backup", e);
        }
    }

    public void importDataBackupJson(String jsonText) {
        Objects.requireNonNull(jsonText, "jsonText");
        JsonObject root = JsonParser.parseString(jsonText).getAsJsonObject();
        JsonArray tables = root.has("tables") && root.get("tables").isJsonArray() ? root.getAsJsonArray("tables") : new JsonArray();

        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try {
                List<String> importedTableNames = new ArrayList<>();

                for (JsonElement tableEl : tables) {
                    if (!tableEl.isJsonObject()) continue;
                    JsonObject tableObj = tableEl.getAsJsonObject();
                    String tableName = str(tableObj, "name");
                    if (tableName.startsWith("sqlite_") || tableName.equals("module_config_schema") || tableName.equals("module_config_values")) {
                        continue;
                    }
                    importedTableNames.add(tableName);

                    String createSql = str(tableObj, "create_sql");
                    try (Statement st = conn.createStatement()) {
                        st.execute(createSql);
                        st.executeUpdate("DELETE FROM " + tableName);
                    }

                    JsonArray rows = tableObj.has("rows") && tableObj.get("rows").isJsonArray() ? tableObj.getAsJsonArray("rows") : new JsonArray();
                    if (rows.isEmpty()) continue;

                    JsonObject first = rows.get(0).getAsJsonObject();
                    List<String> cols = new ArrayList<>(first.keySet());
                    if (cols.isEmpty()) continue;

                    StringBuilder q = new StringBuilder();
                    for (int i = 0; i < cols.size(); i++) {
                        if (i > 0) q.append(',');
                        q.append('?');
                    }
                    String insert = "INSERT INTO " + tableName + " (" + String.join(",", cols) + ") VALUES (" + q + ")";
                    try (PreparedStatement ps = conn.prepareStatement(insert)) {
                        for (JsonElement rowEl : rows) {
                            if (!rowEl.isJsonObject()) continue;
                            JsonObject row = rowEl.getAsJsonObject();
                            for (int i = 0; i < cols.size(); i++) {
                                String col = cols.get(i);
                                JsonElement value = row.get(col);
                                if (value == null || value.isJsonNull()) {
                                    ps.setObject(i + 1, null);
                                } else if (value.isJsonPrimitive()) {
                                    if (value.getAsJsonPrimitive().isBoolean()) {
                                        ps.setBoolean(i + 1, value.getAsBoolean());
                                    } else if (value.getAsJsonPrimitive().isNumber()) {
                                        ps.setObject(i + 1, value.getAsNumber());
                                    } else {
                                        ps.setString(i + 1, value.getAsString());
                                    }
                                } else {
                                    ps.setString(i + 1, gson.toJson(value));
                                }
                            }
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }

                if (!importedTableNames.isEmpty()) {
                    List<String> existing = new ArrayList<>();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('module_config_schema','module_config_values')")) {
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                existing.add(rs.getString("name"));
                            }
                        }
                    }
                    for (String table : existing) {
                        if (!importedTableNames.contains(table)) {
                            try (Statement st = conn.createStatement()) {
                                st.executeUpdate("DELETE FROM " + table);
                            }
                        }
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to import data backup", e);
        }
    }

    private JsonArray queryRowsAsJson(Connection conn, String sql) throws Exception {
        JsonArray out = new JsonArray();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            int cols = md.getColumnCount();
            while (rs.next()) {
                JsonObject row = new JsonObject();
                for (int i = 1; i <= cols; i++) {
                    String col = md.getColumnLabel(i);
                    Object v = rs.getObject(i);
                    row.add(col, gson.toJsonTree(v));
                }
                out.add(row);
            }
        }
        return out;
    }

    private String str(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    private String nullableStr(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return null;
        return obj.get(key).getAsString();
    }

    private int intVal(JsonObject obj, String key, int fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private long longVal(JsonObject obj, String key, long fallback) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

}
