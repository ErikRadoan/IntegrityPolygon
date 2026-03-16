package dev.erikradovan.integritypolygon.core;

import com.google.gson.JsonObject;
import dev.erikradovan.integritypolygon.api.ExtenderMessage;
import dev.erikradovan.integritypolygon.api.ExtenderService;
import dev.erikradovan.integritypolygon.messaging.ExtenderSocketServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.Base64;

/**
 * Implementation of {@link ExtenderService} backed by the length-prefixed
 * TCP tunnel ({@link ExtenderSocketServer}).
 *
 * <p>All incoming frames are routed to module-specific handlers based on
 * the {@code module} and {@code type} fields in the JSON envelope. System
 * messages (heartbeat, announce) are handled internally and also forwarded
 * to any registered module handlers.
 */
public class ExtenderServiceImpl implements ExtenderService {

    private static final long STATE_STALE_MS = 90_000L;

    private final ExtenderSocketServer socketServer;
    private final ProxyServer proxy;
    private final Logger logger;

    /**
     * Handler registry: "moduleId:type" -> handler
     */
    private final ConcurrentHashMap<String, Consumer<ExtenderMessage>> handlers = new ConcurrentHashMap<>();

    /**
     * Backend server state collected from heartbeats & announces.
     * Key = extenderId (hash).
     */
    private final ConcurrentHashMap<String, Map<String, Object>> serverStates = new ConcurrentHashMap<>();

    /**
     * Pending extender module deployments: moduleId -> DeployedModule.
     * Stored so that newly connecting extenders can receive them.
     */
    private final ConcurrentHashMap<String, DeployedModule> pendingModules = new ConcurrentHashMap<>();

    record DeployedModule(String moduleId, String base64Jar, String version) {}

    public ExtenderServiceImpl(ExtenderSocketServer socketServer, ProxyServer proxy, Logger logger) {
        this.socketServer = socketServer;
        this.proxy = proxy;
        this.logger = logger;

        // Wire up the socket server's message handler
        if (socketServer != null) {
            socketServer.setMessageHandler(this::handleIncoming);
        }
    }

    // ── Typed message API ──────────────────────────────────────────

    @Override
    public void sendMessage(String moduleId, String extenderId, String type, JsonObject payload) {
        JsonObject envelope = buildEnvelope(moduleId, type, payload);
        if (socketServer != null) socketServer.sendToExtender(extenderId, envelope);
    }

    @Override
    public void broadcastMessage(String moduleId, String type, JsonObject payload) {
        JsonObject envelope = buildEnvelope(moduleId, type, payload);
        if (socketServer != null) socketServer.sendToAll(envelope);
    }

    @Override
    public void onMessage(String moduleId, String type, Consumer<ExtenderMessage> handler) {
        String key = moduleId + ":" + type;
        handlers.put(key, handler);
        logger.debug("Registered handler: {}", key);
    }

    @Override
    public void removeHandler(String moduleId, String type) {
        handlers.remove(moduleId + ":" + type);
    }

    @Override
    public void removeAllHandlers(String moduleId) {
        String prefix = moduleId + ":";
        handlers.keySet().removeIf(k -> k.startsWith(prefix));
    }

    // ── Convenience: event subscriptions ──────────────────────────

    @Override
    public void subscribeToEvent(String moduleId, String eventType, Consumer<ExtenderMessage> handler) {
        // Register handler for incoming event messages
        onMessage(moduleId, "event:" + eventType, handler);

        // Tell all extenders to start forwarding this event
        JsonObject payload = new JsonObject();
        payload.addProperty("event", eventType);
        broadcastMessage(moduleId, "subscribe", payload);
        logger.debug("Module '{}' subscribed to Paper event '{}'", moduleId, eventType);
    }

    @Override
    public void unsubscribeFromEvent(String moduleId, String eventType) {
        removeHandler(moduleId, "event:" + eventType);

        JsonObject payload = new JsonObject();
        payload.addProperty("event", eventType);
        broadcastMessage(moduleId, "unsubscribe", payload);
    }

    // ── Convenience: targeted commands ────────────────────────────

    @Override
    public void sendCommand(String moduleId, String playerUuid, Map<String, Object> command) {
        Optional<Player> player = proxy.getPlayer(UUID.fromString(playerUuid));
        if (player.isEmpty()) return;

        JsonObject payload = mapToJson(command);

        // Find which extender hosts this player by matching IPs
        player.get().getCurrentServer().ifPresent(conn -> {
            String playerServerIp = conn.getServerInfo().getAddress().getHostString();
            int playerServerPort = conn.getServerInfo().getAddress().getPort();
            
            // Search for an extender whose server_ip and server_port match
            if (socketServer != null) {
                for (String extId : socketServer.getConnectedExtenders()) {
                    Map<String, Object> state = serverStates.get(extId);
                    if (state != null) {
                        String extServerIp = String.valueOf(state.getOrDefault("server_ip", ""));
                        Object portObj = state.getOrDefault("server_port", 0);
                        int extServerPort = 0;
                        if (portObj instanceof Number n) {
                            extServerPort = n.intValue();
                        } else if (portObj != null) {
                            try {
                                extServerPort = Integer.parseInt(String.valueOf(portObj));
                            } catch (NumberFormatException ignored) {}
                        }
                        
                        if (playerServerIp.equalsIgnoreCase(extServerIp) && playerServerPort == extServerPort) {
                            sendMessage(moduleId, extId, "command", payload);
                            return;
                        }
                    }
                }
            }
            // Fallback: broadcast to all
            broadcastMessage(moduleId, "command", payload);
        });
    }

    @Override
    public void broadcastCommand(String moduleId, Map<String, Object> command) {
        JsonObject payload = mapToJson(command);
        broadcastMessage(moduleId, "command", payload);
    }

    // ── Connection info ──────────────────────────────────────────

    @Override
    public Collection<String> getConnectedExtenders() {
        if (socketServer == null) return Set.of();
        return socketServer.getConnectedExtenders();
    }

    @Override
    public Map<String, Map<String, Object>> getServerStates() {
        long now = System.currentTimeMillis();
        Set<String> activeExtenders = socketServer != null
                ? new HashSet<>(socketServer.getConnectedExtenders())
                : Set.of();

        Map<String, Map<String, Object>> filtered = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : serverStates.entrySet()) {
            String extenderId = entry.getKey();
            if (!activeExtenders.contains(extenderId)) {
                continue;
            }

            Map<String, Object> state = entry.getValue();
            long lastHeartbeat = 0L;
            Object value = state.get("last_heartbeat");
            if (value instanceof Number n) {
                lastHeartbeat = n.longValue();
            } else if (value != null) {
                try {
                    lastHeartbeat = Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {}
            }

            if (lastHeartbeat <= 0L || now - lastHeartbeat > STATE_STALE_MS) {
                continue;
            }

            filtered.put(extenderId, state);
        }
        return Collections.unmodifiableMap(filtered);
    }

    // ── Extender module deployment ────────────────────────────────

    @Override
    public void deployExtenderModule(String moduleId, byte[] jarBytes, String version) {
        String base64 = Base64.getEncoder().encodeToString(jarBytes);
        pendingModules.put(moduleId, new DeployedModule(moduleId, base64, version));

        JsonObject payload = new JsonObject();
        payload.addProperty("module_id", moduleId);
        payload.addProperty("jar_data", base64);
        payload.addProperty("version", version);

        // Send deploy_module to all connected extenders
        JsonObject envelope = buildEnvelope("system", "deploy_module", payload);
        if (socketServer != null) socketServer.sendToAll(envelope);

        logger.info("Deployed extender module '{}' v{} ({} bytes) to all extenders",
                moduleId, version, jarBytes.length);
    }

    @Override
    public void undeployExtenderModule(String moduleId) {
        pendingModules.remove(moduleId);

        JsonObject payload = new JsonObject();
        payload.addProperty("module_id", moduleId);

        JsonObject envelope = buildEnvelope("system", "undeploy_module", payload);
        if (socketServer != null) socketServer.sendToAll(envelope);

        logger.info("Undeployed extender module '{}' from all extenders", moduleId);
    }

    // ── Internal: incoming message routing ────────────────────────

    private void handleIncoming(String extenderId, JsonObject envelope) {
        try {
            String module = envelope.has("module") ? envelope.get("module").getAsString() : "system";
            String type = envelope.has("type") ? envelope.get("type").getAsString() : "";
            String source = envelope.has("source") ? envelope.get("source").getAsString() : extenderId;
            String serverLabel = envelope.has("server_label") ? envelope.get("server_label").getAsString()
                    : (socketServer != null ? socketServer.getServerLabel(extenderId) : extenderId);

            // Extract or construct payload
            JsonObject payload;
            if (envelope.has("payload") && envelope.get("payload").isJsonObject()) {
                payload = envelope.getAsJsonObject("payload");
            } else {
                // For simple messages, use the whole envelope minus meta fields as payload
                payload = envelope.deepCopy();
                payload.remove("module");
                payload.remove("type");
                payload.remove("source");
                payload.remove("server_label");
            }

            // Handle system messages internally
            if ("heartbeat".equals(type)) {
                processHeartbeat(source, serverLabel, payload);
            } else if ("extender_announce".equals(type)) {
                processAnnounce(source, serverLabel, payload);
            }

            // Build the typed ExtenderMessage
            ExtenderMessage msg = new ExtenderMessage(source, serverLabel, module, type, payload);

            // Route to exact handler: "module:type"
            Consumer<ExtenderMessage> handler = handlers.get(module + ":" + type);
            if (handler != null) {
                try { handler.accept(msg); }
                catch (Exception e) {
                    logger.error("Error in handler for {}:{}: {}", module, type, e.getMessage());
                }
            }

            // Also route wildcard handlers: "module:*"
            Consumer<ExtenderMessage> wildcardHandler = handlers.get(module + ":*");
            if (wildcardHandler != null) {
                try { wildcardHandler.accept(msg); }
                catch (Exception e) {
                    logger.error("Error in wildcard handler for {}:*: {}", module, e.getMessage());
                }
            }

            // Route to system wildcard handlers: "system:type" for heartbeat/announce
            if (!"system".equals(module)) {
                Consumer<ExtenderMessage> sysHandler = handlers.get("system:" + type);
                if (sysHandler != null) {
                    try { sysHandler.accept(msg); }
                    catch (Exception e) { logger.debug("Error in system handler for {}: {}", type, e.getMessage()); }
                }
            }

        } catch (Exception e) {
            logger.debug("Failed to route extender message: {}", e.getMessage());
        }
    }

    private void processHeartbeat(String extenderId, String serverLabel, JsonObject payload) {
        Map<String, Object> state = serverStates.computeIfAbsent(extenderId, k -> new LinkedHashMap<>());
        state.put("server", serverLabel);
        state.put("extender_id", extenderId);
        state.put("server_ip", payload.has("server_ip") ? payload.get("server_ip").getAsString() : "");
        state.put("server_port", payload.has("server_port") ? payload.get("server_port").getAsInt() : 0);
        state.put("version", payload.has("version") ? payload.get("version").getAsString() : "?");
        state.put("mc_version", payload.has("mc_version") ? payload.get("mc_version").getAsString() : "?");
        state.put("players", payload.has("players") ? payload.get("players").getAsInt() : 0);
        state.put("max_players", payload.has("max_players") ? payload.get("max_players").getAsInt() : 0);
        state.put("tps", payload.has("tps") ? payload.get("tps").getAsDouble() : 0.0);
        state.put("memory_used_mb", payload.has("memory_used_mb") ? payload.get("memory_used_mb").getAsLong() : 0);
        state.put("memory_max_mb", payload.has("memory_max_mb") ? payload.get("memory_max_mb").getAsLong() : 0);
        state.put("cpu_usage", payload.has("cpu_usage") ? payload.get("cpu_usage").getAsDouble() : -1.0);
        state.put("last_heartbeat", System.currentTimeMillis());
        state.put("enrolled", true);
    }

    private void processAnnounce(String extenderId, String serverLabel, JsonObject payload) {
        Map<String, Object> state = serverStates.computeIfAbsent(extenderId, k -> new LinkedHashMap<>());
        state.put("server", serverLabel);
        state.put("extender_id", extenderId);
        state.put("server_ip", payload.has("server_ip") ? payload.get("server_ip").getAsString() : "");
        state.put("server_port", payload.has("server_port") ? payload.get("server_port").getAsInt() : 0);
        state.put("version", payload.has("version") ? payload.get("version").getAsString() : "?");
        state.put("enrolled", true);
        state.put("last_heartbeat", System.currentTimeMillis());
        logger.debug("Extender announced: {} [{}]", serverLabel, extenderId);

        // Auto-deploy pending extender modules to the newly connected extender
        for (DeployedModule mod : pendingModules.values()) {
            JsonObject deployPayload = new JsonObject();
            deployPayload.addProperty("module_id", mod.moduleId());
            deployPayload.addProperty("jar_data", mod.base64Jar());
            deployPayload.addProperty("version", mod.version());
            JsonObject envelope = buildEnvelope("system", "deploy_module", deployPayload);
            if (socketServer != null) socketServer.sendToExtender(extenderId, envelope);
            logger.info("Auto-deployed extender module '{}' to newly connected extender {}", mod.moduleId(), serverLabel);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private JsonObject buildEnvelope(String moduleId, String type, JsonObject payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("module", moduleId);
        envelope.addProperty("type", type);
        if (payload != null) {
            envelope.add("payload", payload);
        }
        return envelope;
    }

    private JsonObject mapToJson(Map<String, Object> map) {
        JsonObject obj = new JsonObject();
        map.forEach((k, v) -> {
            if (v instanceof Number n) obj.addProperty(k, n);
            else if (v instanceof Boolean b) obj.addProperty(k, b);
            else obj.addProperty(k, String.valueOf(v));
        });
        return obj;
    }
}
