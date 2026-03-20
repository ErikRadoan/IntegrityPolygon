package dev.erikradovan.integritypolygon.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.erikradovan.integritypolygon.api.ModuleConfigOption;
import dev.erikradovan.integritypolygon.api.ModuleConfigStore;
import dev.erikradovan.integritypolygon.api.ModuleStorage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQLite-backed centralized module config store.
 */
public class ModuleConfigStoreImpl implements ModuleConfigStore {

    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9_]{1,64}$");

    private final SqliteModuleDatabase db;
    private final Gson gson;
    private final String moduleId;

    public ModuleConfigStoreImpl(SqliteModuleDatabase db, Gson gson, String moduleId) {
        this.db = db;
        this.gson = gson;
        this.moduleId = moduleId;
    }

    @Override
    public void registerOptions(List<ModuleConfigOption> options) {
        if (options == null || options.isEmpty()) return;

        withConnection(conn -> {
            conn.setAutoCommit(false);
            try {
                String upsertSchema = """
                        INSERT INTO module_config_schema
                            (module_id, config_key, type, nullable, description, default_json, allowed_values_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, strftime('%s','now'))
                        ON CONFLICT(module_id, config_key) DO UPDATE SET
                            type = excluded.type,
                            nullable = excluded.nullable,
                            description = excluded.description,
                            default_json = excluded.default_json,
                            allowed_values_json = excluded.allowed_values_json,
                            updated_at = excluded.updated_at
                        """;

                String insertDefault = """
                        INSERT INTO module_config_values (module_id, config_key, value_json, updated_at)
                        VALUES (?, ?, ?, strftime('%s','now'))
                        ON CONFLICT(module_id, config_key) DO NOTHING
                        """;

                try (PreparedStatement schemaPs = conn.prepareStatement(upsertSchema);
                     PreparedStatement defaultPs = conn.prepareStatement(insertDefault)) {
                    for (ModuleConfigOption option : options) {
                        validateKey(option.key());

                        String defaultJson = gson.toJson(option.defaultValue());
                        String allowedValuesJson = gson.toJson(option.allowedValues() == null ? List.of() : option.allowedValues());

                        schemaPs.setString(1, moduleId);
                        schemaPs.setString(2, option.key());
                        schemaPs.setString(3, option.type().name());
                        schemaPs.setInt(4, option.nullable() ? 1 : 0);
                        schemaPs.setString(5, option.description());
                        schemaPs.setString(6, defaultJson);
                        schemaPs.setString(7, allowedValuesJson);
                        schemaPs.addBatch();

                        defaultPs.setString(1, moduleId);
                        defaultPs.setString(2, option.key());
                        defaultPs.setString(3, defaultJson);
                        defaultPs.addBatch();
                    }
                    schemaPs.executeBatch();
                    defaultPs.executeBatch();
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
            return null;
        });
    }

    @Override
    public Map<String, Object> getAll() {
        return withConnection(conn -> {
            Map<String, Object> out = new LinkedHashMap<>();
            String sql = "SELECT config_key, value_json FROM module_config_values WHERE module_id = ? ORDER BY config_key";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, moduleId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString("config_key"), parseJson(rs.getString("value_json")));
                    }
                }
            }
            return out;
        });
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = getRaw(key);
        return value instanceof Boolean b ? b : defaultValue;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        Object value = getRaw(key);
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    @Override
    public double getDouble(String key, double defaultValue) {
        Object value = getRaw(key);
        if (value instanceof Number n) return n.doubleValue();
        if (value instanceof String s) {
            try {
                return Double.parseDouble(s);
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    @Override
    public String getString(String key, String defaultValue) {
        Object value = getRaw(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    @Override
    public List<String> getStringList(String key) {
        Object value = getRaw(key);
        if (value instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object item : list) {
                if (item != null) out.add(String.valueOf(item));
            }
            return out;
        }
        return List.of();
    }

    @Override
    public Object getRaw(String key) {
        validateKey(key);
        return withConnection(conn -> {
            String sql = "SELECT value_json FROM module_config_values WHERE module_id = ? AND config_key = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, moduleId);
                ps.setString(2, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return parseJson(rs.getString("value_json"));
                    }
                }
            }
            return null;
        });
    }

    @Override
    public void set(String key, Object value) {
        validateKey(key);
        withConnection(conn -> {
            String sql = """
                    INSERT INTO module_config_values (module_id, config_key, value_json, updated_at)
                    VALUES (?, ?, ?, strftime('%s','now'))
                    ON CONFLICT(module_id, config_key) DO UPDATE SET
                        value_json = excluded.value_json,
                        updated_at = excluded.updated_at
                    """;
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, moduleId);
                ps.setString(2, key);
                ps.setString(3, gson.toJson(value));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void setAll(Map<String, Object> values) {
        if (values == null || values.isEmpty()) return;
        withConnection(conn -> {
            conn.setAutoCommit(false);
            try {
                String sql = """
                        INSERT INTO module_config_values (module_id, config_key, value_json, updated_at)
                        VALUES (?, ?, ?, strftime('%s','now'))
                        ON CONFLICT(module_id, config_key) DO UPDATE SET
                            value_json = excluded.value_json,
                            updated_at = excluded.updated_at
                        """;
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (Map.Entry<String, Object> entry : values.entrySet()) {
                        validateKey(entry.getKey());
                        ps.setString(1, moduleId);
                        ps.setString(2, entry.getKey());
                        ps.setString(3, gson.toJson(entry.getValue()));
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
            return null;
        });
    }

    private void validateKey(String key) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid config key: " + key);
        }
    }

    private Object parseJson(String valueJson) {
        if (valueJson == null) return null;
        return gson.fromJson(valueJson, new TypeToken<Object>() {}.getType());
    }

    private <T> T withConnection(ModuleStorage.SqlFunction<T> fn) {
        try (Connection conn = db.openConnection()) {
            return fn.apply(conn);
        } catch (Exception e) {
            throw new RuntimeException("Config store operation failed for module " + moduleId, e);
        }
    }
}

