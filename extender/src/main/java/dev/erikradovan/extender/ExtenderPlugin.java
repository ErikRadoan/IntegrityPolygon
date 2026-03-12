package dev.erikradovan.extender;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * IntegrityPolygon Extender — Paper server companion plugin.
 *
 * <p>Communicates with IntegrityPolygon on the Velocity proxy via a
 * length-prefixed TCP tunnel. Each frame is {@code [4-byte big-endian length][UTF-8 JSON]}.
 *
 * <h3>Message Envelope</h3>
 * <pre>{@code
 * {
 *   "module":       "server-monitor",
 *   "type":         "heartbeat",
 *   "source":       "<extender-hash>",
 *   "server_label": "lobby",
 *   "payload":      { … }
 * }
 * }</pre>
 *
 * <p>The extender generates a random identity hash on first launch and
 * persists it to {@code extender-identity.dat}. This hash uniquely
 * identifies the extender across reconnections and is <em>not</em> used
 * for authentication (the Velocity forwarding secret handles that).
 */
public class ExtenderPlugin extends JavaPlugin implements Listener {

    private final Gson gson = new Gson();

    // Per-module event subscriptions: eventType -> Set<moduleId>
    private final ConcurrentHashMap<String, Set<String>> eventSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastEventSent = new ConcurrentHashMap<>();
    private static final long EVENT_THROTTLE_MS = 500;

    // Persistent extender identity hash
    private String extenderId;
    // Human-readable label
    private String serverLabel;

    // Players frozen for 2FA
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    // TPS tracking
    private long lastTickTime = System.nanoTime();
    private final double[] recentTps = new double[]{20.0, 20.0, 20.0};
    private int tickCount = 0;

    // TCP connection (length-prefixed)
    private volatile Socket proxySocket;
    private volatile DataOutputStream socketOut;
    private volatile DataInputStream socketIn;
    private volatile boolean socketConnected = false;
    private volatile boolean running = true;
    private Thread socketThread;

    // Extender module manager
    private ExtenderModuleManager extModuleManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Resolve persistent identity hash
        extenderId = resolveIdentity();

        // Resolve human-readable label
        String cfgLabel = getConfig().getString("server-label", "").trim();
        if (cfgLabel.isEmpty()) {
            cfgLabel = getConfig().getString("extender-id", "").trim();
        }
        serverLabel = cfgLabel.isEmpty() ? getServer().getName() : cfgLabel;

        // Initialize extender module manager
        extModuleManager = new ExtenderModuleManager(this, extenderId, serverLabel,
                (id, envelope) -> sendFrame(envelope));
        extModuleManager.loadAll();

        getServer().getPluginManager().registerEvents(this, this);

        // TPS measurement
        lastTickTime = System.nanoTime();
        getServer().getScheduler().runTaskTimer(this, () -> {
            tickCount++;
            if (tickCount >= 100) {
                long now = System.nanoTime();
                double elapsed = (now - lastTickTime) / 1_000_000_000.0;
                double tps = Math.min(20.0, tickCount / elapsed);
                recentTps[0] = recentTps[1];
                recentTps[1] = recentTps[2];
                recentTps[2] = tps;
                lastTickTime = now;
                tickCount = 0;
            }
        }, 1L, 1L);

        // Start TCP socket (auto-reconnect)
        socketThread = new Thread(this::socketLoop, "IP-ExtenderSocket");
        socketThread.setDaemon(true);
        socketThread.start();

        // Periodic heartbeat every 30s
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            if (socketConnected) {
                sendFrame(buildHeartbeat());
            }
        }, 600L, 600L);

        getLogger().info("IntegrityPolygon Extender enabled [id=" +
                extenderId.substring(0, Math.min(8, extenderId.length())) + ", label=" + serverLabel + "]");
    }

    @Override
    public void onDisable() {
        running = false;
        if (extModuleManager != null) extModuleManager.unloadAll();
        closeSocket();
        getLogger().info("IntegrityPolygon Extender disabled");
    }

    // ════════════════════════════════════════════════════════════════
    //  IDENTITY — persistent random hash
    // ════════════════════════════════════════════════════════════════

    private String resolveIdentity() {
        Path idFile = getDataFolder().toPath().resolve("extender-identity.dat");
        // Try to read existing
        if (Files.exists(idFile)) {
            try {
                String id = Files.readString(idFile, StandardCharsets.UTF_8).trim();
                if (!id.isEmpty()) {
                    getLogger().info("Loaded extender identity: " + id.substring(0, Math.min(8, id.length())) + "…");
                    return id;
                }
            } catch (IOException e) {
                getLogger().warning("Could not read identity file: " + e.getMessage());
            }
        }
        // Generate new
        String id = UUID.randomUUID().toString().replace("-", "")
                + Long.toHexString(System.nanoTime());
        try {
            Files.createDirectories(idFile.getParent());
            Files.writeString(idFile, id, StandardCharsets.UTF_8);
            getLogger().info("Generated new extender identity: " + id.substring(0, 8) + "…");
        } catch (IOException e) {
            getLogger().warning("Could not persist identity: " + e.getMessage());
        }
        return id;
    }

    // ════════════════════════════════════════════════════════════════
    //  TCP SOCKET — length-prefixed framing
    // ════════════════════════════════════════════════════════════════

    private void socketLoop() {
        String host = getConfig().getString("proxy-host", "velocity");
        int port = getConfig().getInt("proxy-port", 3491);
        String secret = resolveExtenderSecret();

        if (secret.isEmpty()) {
            getLogger().warning("Could not determine extender secret!");
            getLogger().warning("Set 'secret' in plugins/IntegrityPolygon-Extender/config.yml,");
            getLogger().warning("or ensure Velocity modern forwarding is configured.");
            return;
        }

        while (running) {
            try {
                getLogger().info("Connecting to proxy at " + host + ":" + port + "...");
                proxySocket = new Socket(host, port);
                socketIn = new DataInputStream(new BufferedInputStream(proxySocket.getInputStream()));
                socketOut = new DataOutputStream(new BufferedOutputStream(proxySocket.getOutputStream()));

                // Auth handshake
                JsonObject auth = new JsonObject();
                auth.addProperty("type", "auth");
                auth.addProperty("secret", secret);
                auth.addProperty("extender_id", extenderId);
                auth.addProperty("server_label", serverLabel);
                auth.addProperty("version", getDescription().getVersion());
                writeFrame(auth);

                // Read auth response
                String response = readFrame();
                if (response == null) {
                    getLogger().warning("Proxy closed connection during auth");
                    closeSocket();
                    sleep(10000);
                    continue;
                }

                JsonObject resp = JsonParser.parseString(response).getAsJsonObject();
                String respType = resp.has("type") ? resp.get("type").getAsString() : "";
                if (!"auth_ok".equals(respType)) {
                    String reason = resp.has("reason") ? resp.get("reason").getAsString() : "unknown";
                    getLogger().severe("Proxy auth failed: " + reason);
                    closeSocket();
                    sleep(30000);
                    continue;
                }

                socketConnected = true;
                getLogger().info("Connected to proxy via TCP tunnel!");

                // Send initial heartbeat
                sendFrame(buildHeartbeat());

                // Read incoming messages
                while (running) {
                    String frame = readFrame();
                    if (frame == null) break;
                    handleIncomingMessage(frame);
                }

            } catch (Exception e) {
                if (running) {
                    getLogger().warning("Socket connection lost: " + e.getMessage() + ". Reconnecting in 10s...");
                }
            } finally {
                socketConnected = false;
                closeSocket();
            }

            if (running) sleep(10000);
        }
    }

    // ── Incoming message handling ────────────────────────────────

    private void handleIncomingMessage(String json) {
        try {
            JsonObject envelope = gson.fromJson(json, JsonObject.class);
            String module = envelope.has("module") ? envelope.get("module").getAsString() : "system";
            String type = envelope.has("type") ? envelope.get("type").getAsString() : "";

            // Extract payload
            JsonObject payload;
            if (envelope.has("payload") && envelope.get("payload").isJsonObject()) {
                payload = envelope.getAsJsonObject("payload");
            } else {
                payload = envelope.deepCopy();
                payload.remove("module");
                payload.remove("type");
                payload.remove("source");
                payload.remove("server_label");
            }

            switch (type) {
                case "subscribe" -> handleSubscribe(module, payload);
                case "unsubscribe" -> handleUnsubscribe(module, payload);
                case "command" -> {
                    boolean handled = handleCommand(module, payload);
                    if (!handled && extModuleManager != null && extModuleManager.routeMessage(module, type, payload)) {
                        break;
                    }
                    if (!handled) {
                        getLogger().fine("Unhandled command for module '" + module + "'");
                    }
                }
                case "heartbeat_request" -> sendFrame(buildHeartbeat());
                case "deploy_module" -> {
                    if (extModuleManager != null) extModuleManager.deployModule(payload);
                }
                case "undeploy_module" -> {
                    String modId = payload.has("module_id") ? payload.get("module_id").getAsString() : "";
                    if (extModuleManager != null && !modId.isEmpty()) extModuleManager.undeployModule(modId);
                }
                default -> {
                    // Try routing to extender module
                    if (extModuleManager != null && extModuleManager.routeMessage(module, type, payload)) {
                        break;
                    }
                    getLogger().fine("Unhandled message: " + module + ":" + type);
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to process message: " + json, e);
        }
    }

    private void handleSubscribe(String module, JsonObject payload) {
        String event = payload.has("event") ? payload.get("event").getAsString() : "";
        if (event.isBlank()) return;
        eventSubscriptions.computeIfAbsent(event, k -> ConcurrentHashMap.newKeySet()).add(module);
        getLogger().info("Module '" + module + "' subscribed to " + event);
    }

    private void handleUnsubscribe(String module, JsonObject payload) {
        String event = payload.has("event") ? payload.get("event").getAsString() : "";
        Set<String> subs = eventSubscriptions.get(event);
        if (subs != null) {
            subs.remove(module);
            if (subs.isEmpty()) eventSubscriptions.remove(event);
        }
    }

    private boolean handleCommand(String module, JsonObject payload) {
        String action = payload.has("action") ? payload.get("action").getAsString() : "";
        switch (action) {
            case "kick" -> {
                handleKick(payload);
                return true;
            }
            case "message" -> {
                handlePlayerMessage(payload);
                return true;
            }
            case "show_2fa_prompt" -> {
                handleShow2faPrompt(payload);
                return true;
            }
            case "2fa_verified" -> {
                handle2faVerified(payload);
                return true;
            }
            case "ping" -> {
                handlePing(module);
                return true;
            }
            case "profile" -> {
                handleProfile(module, payload);
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    // ── Command handlers ────────────────────────────────────────

    private void handleKick(JsonObject payload) {
        String playerName = payload.has("player") ? payload.get("player").getAsString() : "";
        String reason = payload.has("reason") ? payload.get("reason").getAsString() : "Disconnected by security system.";
        Player target = Bukkit.getPlayerExact(playerName);
        if (target != null) {
            Bukkit.getScheduler().runTask(this, () -> target.kickPlayer(reason));
        }
    }

    private void handlePlayerMessage(JsonObject payload) {
        String playerName = payload.has("player") ? payload.get("player").getAsString() : "";
        String text = payload.has("text") ? payload.get("text").getAsString() : "";
        Player target = Bukkit.getPlayerExact(playerName);
        if (target != null) target.sendMessage(text);
    }

    private void handleShow2faPrompt(JsonObject payload) {
        String playerName = payload.has("player") ? payload.get("player").getAsString() : "";
        int timeoutSec = payload.has("timeout") ? payload.get("timeout").getAsInt() : 60;
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) return;

        UUID uuid = target.getUniqueId();
        frozenPlayers.add(uuid);

        Bukkit.getScheduler().runTask(this, () -> {
            target.setWalkSpeed(0f);
            target.setFlySpeed(0f);
            target.sendTitle("\u00a76\u26a0 2FA Required",
                    "\u00a7eType \u00a7b/2fa <code> \u00a7eto verify", 10, 200, 20);
            target.sendMessage("");
            target.sendMessage("\u00a78\u00a7m                                                  ");
            target.sendMessage("  \u00a76\u00a7l\u26a0 Two-Factor Authentication Required");
            target.sendMessage("");
            target.sendMessage("  \u00a77Open your authenticator app and enter the code.");
            target.sendMessage("  \u00a7eCommand: \u00a7b/2fa <6-digit code>");
            target.sendMessage("  \u00a7cYou will be kicked in " + timeoutSec + " seconds if not verified.");
            target.sendMessage("\u00a78\u00a7m                                                  ");

            int taskId = Bukkit.getScheduler().scheduleSyncRepeatingTask(this, () -> {
                if (target.isOnline() && frozenPlayers.contains(uuid)) {
                    target.sendTitle("\u00a76\u26a0 2FA Required",
                            "\u00a7eType \u00a7b/2fa <code> \u00a7eto verify", 0, 220, 20);
                }
            }, 200L, 200L);

            Bukkit.getScheduler().runTaskLater(this, () -> {
                Bukkit.getScheduler().cancelTask(taskId);
                unfreezePlayer(target);
            }, timeoutSec * 20L);
        });
    }

    private void handle2faVerified(JsonObject payload) {
        String playerName = payload.has("player") ? payload.get("player").getAsString() : "";
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) return;

        Bukkit.getScheduler().runTask(this, () -> {
            unfreezePlayer(target);
            target.sendTitle("\u00a7a\u2713 Verified",
                    "\u00a7aWelcome back!", 10, 60, 20);
        });
    }

    private void unfreezePlayer(Player player) {
        frozenPlayers.remove(player.getUniqueId());
        player.setWalkSpeed(0.2f);
        player.setFlySpeed(0.1f);
    }

    private void handlePing(String module) {
        JsonObject payload = new JsonObject();
        payload.addProperty("server", serverLabel);
        payload.addProperty("players", getServer().getOnlinePlayers().size());
        payload.addProperty("version", getServer().getVersion());
        sendEnvelope(module, "pong", payload);
    }

    private void handleProfile(String module, JsonObject payload) {
        int topN = payload.has("top_chunks") ? payload.get("top_chunks").getAsInt() : 10;

        getServer().getScheduler().runTask(this, () -> {
            try {
                JsonObject report = new JsonObject();

                // Chunk data
                com.google.gson.JsonArray chunks = new com.google.gson.JsonArray();
                for (var world : getServer().getWorlds()) {
                    for (var chunk : world.getLoadedChunks()) {
                        if (!chunk.isLoaded()) continue;
                        JsonObject ci = new JsonObject();
                        ci.addProperty("world", world.getName());
                        ci.addProperty("x", chunk.getX());
                        ci.addProperty("z", chunk.getZ());
                        ci.addProperty("entity_count", chunk.getEntities().length);
                        ci.addProperty("tile_entity_count", chunk.getTileEntities().length);
                        double estimatedMs = chunk.getEntities().length * 0.05 + chunk.getTileEntities().length * 0.1;
                        ci.addProperty("tick_ms", Math.round(estimatedMs * 100.0) / 100.0);
                        com.google.gson.JsonArray nearby = new com.google.gson.JsonArray();
                        for (var entity : chunk.getEntities()) {
                            if (entity instanceof Player p) nearby.add(p.getName());
                        }
                        ci.add("players_nearby", nearby);
                        chunks.add(ci);
                    }
                }

                List<JsonObject> sorted = new ArrayList<>();
                for (int i = 0; i < chunks.size(); i++) sorted.add(chunks.get(i).getAsJsonObject());
                sorted.sort((a, b) -> Double.compare(b.get("tick_ms").getAsDouble(), a.get("tick_ms").getAsDouble()));
                com.google.gson.JsonArray topChunks = new com.google.gson.JsonArray();
                for (int i = 0; i < Math.min(topN, sorted.size()); i++) topChunks.add(sorted.get(i));
                report.add("chunks", topChunks);

                // Plugin data
                com.google.gson.JsonArray plugins = new com.google.gson.JsonArray();
                for (var plugin : getServer().getPluginManager().getPlugins()) {
                    if (!plugin.isEnabled()) continue;
                    JsonObject pi = new JsonObject();
                    pi.addProperty("name", plugin.getName());
                    int eventCount = 0;
                    try {
                        var listeners = org.bukkit.event.HandlerList.getRegisteredListeners(plugin);
                        eventCount = listeners.size();
                    } catch (Exception ignored) {}
                    pi.addProperty("event_count", eventCount);
                    pi.addProperty("tick_ms", Math.round(eventCount * 0.02 * 100.0) / 100.0);
                    plugins.add(pi);
                }
                report.add("plugins", plugins);

                // Send as proper envelope
                JsonObject responsePayload = new JsonObject();
                responsePayload.addProperty("server", serverLabel);
                responsePayload.addProperty("report", gson.toJson(report));
                sendEvent(module, "profiling_report", responsePayload);

            } catch (Exception e) {
                getLogger().warning("Profiling error: " + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════════════════════
    //  PAPER EVENT FORWARDING
    // ════════════════════════════════════════════════════════════════

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        forwardEvent("PlayerJoinEvent", Map.of(
                "player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString(),
                "ip", Objects.toString(event.getPlayer().getAddress(), "")));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerLogin(PlayerLoginEvent event) {
        forwardEvent("PlayerLoginEvent", Map.of(
                "player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString(),
                "ip", Objects.toString(event.getAddress(), ""),
                "result", event.getResult().name()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        frozenPlayers.remove(event.getPlayer().getUniqueId());
        forwardEvent("PlayerQuitEvent", Map.of(
                "player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString()));
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (frozenPlayers.contains(event.getPlayer().getUniqueId())) {
            if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                    || event.getFrom().getBlockY() != event.getTo().getBlockY()
                    || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
                event.setCancelled(true);
            }
            return;
        }

        if (!eventSubscriptions.containsKey("PlayerMoveEvent")) return;
        String key = "move:" + event.getPlayer().getName();
        long now = System.currentTimeMillis();
        Long last = lastEventSent.get(key);
        if (last != null && (now - last) < EVENT_THROTTLE_MS) return;
        lastEventSent.put(key, now);
        forwardEvent("PlayerMoveEvent", Map.of(
                "player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString(),
                "from_x", event.getFrom().getX(),
                "from_z", event.getFrom().getZ(),
                "to_x", event.getTo().getX(),
                "to_z", event.getTo().getZ()));
    }

    // ════════════════════════════════════════════════════════════════
    //  MESSAGE BUILDERS & SENDERS
    // ════════════════════════════════════════════════════════════════

    /**
     * Build and send a proper envelope to the proxy.
     */
    private void sendEnvelope(String module, String type, JsonObject payload) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("module", module);
        envelope.addProperty("type", type);
        envelope.addProperty("source", extenderId);
        envelope.addProperty("server_label", serverLabel);
        envelope.add("payload", payload);
        sendFrame(envelope);
    }

    /**
     * Forward a Paper event as an envelope with type "event:{eventType}".
     */
    private void forwardEvent(String eventType, Map<String, Object> data) {
        Set<String> subscribers = eventSubscriptions.get(eventType);
        if (subscribers == null || subscribers.isEmpty()) return;

        JsonObject payload = new JsonObject();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (e.getValue() instanceof Number n) payload.addProperty(e.getKey(), n);
            else payload.addProperty(e.getKey(), String.valueOf(e.getValue()));
        }
        payload.addProperty("event_type", eventType);

        // Send one envelope per subscriber module
        for (String module : subscribers) {
            sendEnvelope(module, "event:" + eventType, payload);
        }
    }

    /**
     * Send an event response (e.g. profiling_report) — addressed to a specific module.
     */
    private void sendEvent(String module, String eventType, JsonObject payload) {
        sendEnvelope(module, "event:" + eventType, payload);
    }

    private JsonObject buildHeartbeat() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        double avgTps = Math.round(((recentTps[0] + recentTps[1] + recentTps[2]) / 3.0) * 100.0) / 100.0;

        double cpuUsage = -1;
        try {
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                cpuUsage = Math.round(sunBean.getProcessCpuLoad() * 10000.0) / 100.0;
            }
        } catch (Exception ignored) {}

        JsonObject payload = new JsonObject();
        payload.addProperty("version", getDescription().getVersion());
        payload.addProperty("mc_version", getServer().getVersion());
        payload.addProperty("players", getServer().getOnlinePlayers().size());
        payload.addProperty("max_players", getServer().getMaxPlayers());
        payload.addProperty("tps", avgTps);
        payload.addProperty("memory_used_mb", usedMb);
        payload.addProperty("memory_max_mb", maxMb);
        payload.addProperty("cpu_usage", cpuUsage);
        payload.addProperty("timestamp", System.currentTimeMillis());

        JsonObject envelope = new JsonObject();
        envelope.addProperty("module", "system");
        envelope.addProperty("type", "heartbeat");
        envelope.addProperty("source", extenderId);
        envelope.addProperty("server_label", serverLabel);
        envelope.add("payload", payload);
        return envelope;
    }

    // ════════════════════════════════════════════════════════════════
    //  LENGTH-PREFIXED FRAMING
    // ════════════════════════════════════════════════════════════════

    private synchronized void sendFrame(JsonObject msg) {
        if (socketOut == null) return;
        try {
            writeFrame(msg);
        } catch (IOException e) {
            getLogger().warning("Socket write failed: " + e.getMessage());
            socketConnected = false;
        }
    }

    private void writeFrame(JsonObject msg) throws IOException {
        byte[] data = gson.toJson(msg).getBytes(StandardCharsets.UTF_8);
        socketOut.writeInt(data.length);
        socketOut.write(data);
        socketOut.flush();
    }

    private String readFrame() throws IOException {
        int length;
        try {
            length = socketIn.readInt();
        } catch (EOFException e) {
            return null;
        }
        if (length <= 0 || length > 16 * 1024 * 1024) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] data = new byte[length];
        socketIn.readFully(data);
        return new String(data, StandardCharsets.UTF_8);
    }

    private void closeSocket() {
        socketConnected = false;
        try { if (proxySocket != null) proxySocket.close(); } catch (IOException ignored) {}
        proxySocket = null;
        socketOut = null;
        socketIn = null;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    // ════════════════════════════════════════════════════════════════
    //  SECRET RESOLUTION
    // ════════════════════════════════════════════════════════════════

    private String resolveExtenderSecret() {
        // 1. Explicit override in extender config
        String configSecret = getConfig().getString("secret", "").trim();
        if (!configSecret.isEmpty()) {
            getLogger().info("Using extender secret from config.yml");
            return configSecret;
        }

        // 2. PAPER_VELOCITY_SECRET env var
        String envSecret = System.getenv("PAPER_VELOCITY_SECRET");
        if (envSecret != null && !envSecret.isBlank()) {
            getLogger().info("Using Velocity forwarding secret from PAPER_VELOCITY_SECRET env var");
            return envSecret.trim();
        }

        // 3. Read from Paper's paper-global.yml
        String[] configPaths = {"config/paper-global.yml", "paper-global.yml"};
        for (String path : configPaths) {
            try {
                File paperGlobal = new File(path);
                if (paperGlobal.exists()) {
                    String content = new String(java.nio.file.Files.readAllBytes(paperGlobal.toPath()), StandardCharsets.UTF_8);
                    java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                            "velocity:[\\s\\S]*?secret:\\s*['\"]?([^'\"\\s#]+)['\"]?"
                    ).matcher(content);
                    if (matcher.find()) {
                        String velocitySecret = matcher.group(1).trim();
                        if (!velocitySecret.isEmpty() && !velocitySecret.equals("''") && !velocitySecret.equals("\"\"")) {
                            getLogger().info("Using Velocity forwarding secret from " + path);
                            return velocitySecret;
                        }
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Could not read " + path + ": " + e.getMessage());
            }
        }

        return "";
    }
}

