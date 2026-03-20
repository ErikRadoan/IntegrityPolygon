package dev.erikradovan.integritypolygon.api;

import java.util.List;
import java.util.Objects;

/**
 * Declarative module config option metadata stored in the centralized config schema table.
 */
public record ModuleConfigOption(
        String key,
        ConfigValueType type,
        Object defaultValue,
        boolean nullable,
        String description,
        List<String> allowedValues
) {

    public ModuleConfigOption {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
    }

    public static ModuleConfigOption string(String key, String defaultValue, boolean nullable, String description) {
        return new ModuleConfigOption(key, ConfigValueType.STRING, defaultValue, nullable, description, List.of());
    }

    public static ModuleConfigOption integer(String key, int defaultValue, boolean nullable, String description) {
        return new ModuleConfigOption(key, ConfigValueType.INTEGER, defaultValue, nullable, description, List.of());
    }

    public static ModuleConfigOption decimal(String key, double defaultValue, boolean nullable, String description) {
        return new ModuleConfigOption(key, ConfigValueType.DOUBLE, defaultValue, nullable, description, List.of());
    }

    public static ModuleConfigOption bool(String key, boolean defaultValue, boolean nullable, String description) {
        return new ModuleConfigOption(key, ConfigValueType.BOOLEAN, defaultValue, nullable, description, List.of());
    }

    public static ModuleConfigOption list(String key, List<String> defaultValue, boolean nullable, String description) {
        return new ModuleConfigOption(key, ConfigValueType.LIST, defaultValue, nullable, description, List.of());
    }

    public static ModuleConfigOption json(String key, Object defaultValue, boolean nullable, String description) {
        return new ModuleConfigOption(key, ConfigValueType.JSON, defaultValue, nullable, description, List.of());
    }
}
