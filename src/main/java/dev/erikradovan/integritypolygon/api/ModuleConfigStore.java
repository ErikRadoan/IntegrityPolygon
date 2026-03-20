package dev.erikradovan.integritypolygon.api;

import java.util.List;
import java.util.Map;

/**
 * Module-scoped access to centralized SQLite-backed config schema and values.
 */
public interface ModuleConfigStore {

    void registerOptions(List<ModuleConfigOption> options);

    Map<String, Object> getAll();

    boolean getBoolean(String key, boolean defaultValue);

    int getInt(String key, int defaultValue);

    double getDouble(String key, double defaultValue);

    String getString(String key, String defaultValue);

    List<String> getStringList(String key);

    Object getRaw(String key);

    void set(String key, Object value);

    void setAll(Map<String, Object> values);
}
