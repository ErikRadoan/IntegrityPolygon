package dev.erikradovan.integritypolygon.api;

import com.google.gson.JsonObject;

/**
 * A typed message received from (or to be sent to) a backend Extender.
 *
 * <p>Every message flowing through the Extender TCP tunnel is wrapped in this
 * envelope. Modules register handlers for specific {@code type} values via
 * {@link ExtenderService#onMessage}.
 *
 * @param source     the unique identity hash of the extender that sent this message
 *                   (auto-generated on first launch of each extender instance)
 * @param serverLabel human-readable server label (e.g. "lobby", "survival")
 * @param module     the module this message is addressed to / originated from
 * @param type       the message type (e.g. "heartbeat", "event", "command_response")
 * @param payload    the JSON payload — contents are module-specific
 */
public record ExtenderMessage(
        String source,
        String serverLabel,
        String module,
        String type,
        JsonObject payload
) {

    /**
     * Convenience helper to get a string from the payload.
     */
    public String getString(String key) {
        if (payload == null || !payload.has(key)) return null;
        return payload.get(key).isJsonNull() ? null : payload.get(key).getAsString();
    }

    /**
     * Convenience helper to get an int from the payload.
     */
    public int getInt(String key, int defaultValue) {
        if (payload == null || !payload.has(key)) return defaultValue;
        try { return payload.get(key).getAsInt(); } catch (Exception e) { return defaultValue; }
    }

    /**
     * Convenience helper to get a double from the payload.
     */
    public double getDouble(String key, double defaultValue) {
        if (payload == null || !payload.has(key)) return defaultValue;
        try { return payload.get(key).getAsDouble(); } catch (Exception e) { return defaultValue; }
    }

    /**
     * Convenience helper to get a boolean from the payload.
     */
    public boolean getBoolean(String key, boolean defaultValue) {
        if (payload == null || !payload.has(key)) return defaultValue;
        try { return payload.get(key).getAsBoolean(); } catch (Exception e) { return defaultValue; }
    }
}

