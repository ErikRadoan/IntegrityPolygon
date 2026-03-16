package dev.erikradovan.integritypolygon.api;

import com.google.gson.JsonObject;

import java.util.Collection;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Service for communicating with IntegrityPolygon Extenders on backend servers.
 *
 * <p>All communication flows through a length-prefixed TCP tunnel. Each
 * message is a JSON envelope with {@code module}, {@code type}, {@code source},
 * and {@code payload} fields.  Modules interact with the tunnel exclusively
 * through this service — they never touch sockets directly.
 *
 * <h3>Sending messages</h3>
 * <pre>{@code
 * ExtenderService ext = ctx.getServiceRegistry().get(ExtenderService.class).orElse(null);
 * if (ext != null) {
 *     // Send to a specific extender
 *     JsonObject payload = new JsonObject();
 *     payload.addProperty("action", "kick");
 *     payload.addProperty("player", "Steve");
 *     ext.sendMessage("anti-bot", "a3f8…", "command", payload);
 *
 *     // Broadcast to every connected extender
 *     ext.broadcastMessage("server-monitor", "profile", payload);
 * }
 * }</pre>
 *
 * <h3>Receiving messages</h3>
 * <pre>{@code
 * ext.onMessage("server-monitor", "heartbeat", msg -> {
 *     double tps = msg.getDouble("tps", 20.0);
 *     String server = msg.serverLabel();
 * });
 * }</pre>
 *
 * <p>Obtain via: {@code context.getServiceRegistry().get(ExtenderService.class)}
 */
public interface ExtenderService {

    // ── Typed message API ──────────────────────────────────────────────

    /**
     * Send a message to a specific extender identified by its unique hash.
     *
     * @param moduleId   the sending module's ID
     * @param extenderId the target extender's identity hash
     * @param type       a message type string (e.g. "command", "profile")
     * @param payload    the JSON payload (module-defined contents)
     */
    void sendMessage(String moduleId, String extenderId, String type, JsonObject payload);

    /**
     * Broadcast a message to <em>all</em> connected extenders.
     *
     * @param moduleId the sending module's ID
     * @param type     a message type string
     * @param payload  the JSON payload
     */
    void broadcastMessage(String moduleId, String type, JsonObject payload);

    /**
     * Register a handler that receives messages addressed to a specific
     * module with a specific type.
     *
     * <p>Only one handler may exist per {@code (moduleId, type)} pair; calling
     * this again with the same pair replaces the previous handler.
     *
     * @param moduleId the listening module's ID
     * @param type     the message type to listen for
     * @param handler  callback invoked on the IO thread — offload heavy work
     */
    void onMessage(String moduleId, String type, Consumer<ExtenderMessage> handler);

    /**
     * Remove a previously registered message handler.
     *
     * @param moduleId the module's ID
     * @param type     the message type
     */
    void removeHandler(String moduleId, String type);

    /**
     * Remove <em>all</em> handlers registered by a module (called on module unload).
     *
     * @param moduleId the module's ID
     */
    void removeAllHandlers(String moduleId);

    // ── Convenience: event subscriptions ──────────────────────────────

    /**
     * Subscribe to a Paper event on all backend servers running an Extender.
     * This is a convenience wrapper that tells every extender to start
     * forwarding the named event, and registers a handler for incoming events.
     *
     * @param moduleId  the module requesting the subscription
     * @param eventType the Paper event class name (e.g. "PlayerJoinEvent")
     * @param handler   callback when the event fires on any backend server
     */
    void subscribeToEvent(String moduleId, String eventType, Consumer<ExtenderMessage> handler);

    /**
     * Unsubscribe from a Paper event.
     */
    void unsubscribeFromEvent(String moduleId, String eventType);

    // ── Convenience: targeted commands (legacy-style) ─────────────────

    /**
     * Send a command to the extender that hosts a specific player.
     *
     * @param moduleId   the sending module
     * @param playerUuid the target player's UUID string
     * @param command    command data (will be converted to JsonObject)
     */
    void sendCommand(String moduleId, String playerUuid, Map<String, Object> command);

    /**
     * Broadcast a command to all connected extenders.
     *
     * @param moduleId the sending module
     * @param command  command data (will be converted to JsonObject)
     */
    void broadcastCommand(String moduleId, Map<String, Object> command);

    // ── Connection info ───────────────────────────────────────────────

    /**
     * @return identity hashes of all currently connected extenders
     */
    Collection<String> getConnectedExtenders();

    /**
     * @return per-extender server state collected from heartbeats/announces
     */
    Map<String, Map<String, Object>> getServerStates();

    // ── Extender module deployment ────────────────────────────────────

    /**
     * Deploy an extender module JAR to all connected extenders.
     * The JAR bytes are Base64-encoded and sent via the TCP tunnel.
     * The extender saves the JAR to disk and hot-loads it.
     *
     * <p>Newly connecting extenders will also receive the module automatically.
     *
     * @param moduleId the extender module's ID (must match extender-module.json)
     * @param jarBytes the raw JAR file bytes
     * @param version  the version string (for logging)
     */
    void deployExtenderModule(String moduleId, byte[] jarBytes, String version);

    /**
     * Undeploy an extender module from all connected extenders.
     *
     * @param moduleId the extender module's ID
     */
    void undeployExtenderModule(String moduleId);
}
