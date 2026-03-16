package dev.erikradovan.extender.api;

import com.google.gson.JsonObject;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

/**
 * Context provided to each {@link ExtenderModule} on enable.
 * Gives access to the Paper plugin, messaging, logging, and data storage.
 */
public interface ExtenderModuleContext {

    /**
     * @return the IntegrityPolygon Extender plugin instance (for scheduling, events, etc.)
     */
    JavaPlugin getPlugin();

    /**
     * @return the module's ID (from extender-module.json)
     */
    String getModuleId();

    /**
     * @return an isolated data directory for this module
     */
    Path getDataDirectory();

    /**
     * @return a logger prefixed with this module's ID
     */
    Logger getLogger();

    /**
     * Send a message to the proxy addressed to the module with the given type.
     * The envelope is automatically stamped with the extender's identity and server label.
     *
     * @param type    the message type (e.g. "profiling_data")
     * @param payload the JSON payload
     */
    void sendMessage(String type, JsonObject payload);

    /**
     * @return the human-readable server label (e.g. "lobby")
     */
    String getServerLabel();

    /**
     * @return the unique extender identity hash
     */
    String getExtenderId();

    /**
     * Register a handler for incoming messages from the proxy addressed to this module.
     *
     * @param handler receives (type, payload) for each incoming message
     */
    void onMessage(BiConsumer<String, JsonObject> handler);
}

