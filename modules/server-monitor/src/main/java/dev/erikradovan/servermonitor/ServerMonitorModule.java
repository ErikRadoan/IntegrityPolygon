package dev.erikradovan.servermonitor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erikradovan.integritypolygon.api.AlertService;
import dev.erikradovan.integritypolygon.api.ExtenderMessage;
import dev.erikradovan.integritypolygon.api.ExtenderService;
import dev.erikradovan.integritypolygon.api.MetricsService;
import dev.erikradovan.integritypolygon.api.ModuleContext;
import dev.erikradovan.integritypolygon.api.ModuleDashboard;
import dev.erikradovan.integritypolygon.api.ServiceRegistry;
import dev.erikradovan.integritypolygon.api.ModuleDashboard.RequestContext;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.logging.LogManager;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server Monitor Module — real-time backend performance profiling.
 *
 * <p>Subscribes to Extender heartbeats and profiling events to collect:
 * <ul>
 *   <li>TPS, memory, CPU per backend server</li>
 *   <li>Chunk tick costs (identifies lag-causing chunks)</li>
 *   <li>Plugin tick times (identifies slow plugins)</li>
 *   <li>Lag spike detection with automatic player attribution</li>
 * </ul>
 *
 * <p>All data is collected passively from Extender heartbeats — the Extender
 * already sends TPS, memory, CPU. This module extends it by requesting
 * chunk/plugin profiling data via the ExtenderService command channel.
 */
public class ServerMonitorModule implements dev.erikradovan.integritypolygon.api.Module {

    private ModuleContext context;
    private Logger logger;
    private final Gson gson = new Gson();

    // Services
    private ExtenderService extenderService;
    private MetricsService metricsService;
    private AlertService alertService;
    private ConfigManager configManager;
    private LogManager logManager;

    // Config
    private volatile boolean enabled = true;
    private volatile double tpsWarnThreshold = 18.0;
    private volatile double tpsCriticalThreshold = 15.0;
    private volatile int memoryWarnPercent = 85;
    private volatile int lagSpikeThresholdMs = 100; // chunk tick > this = lag spike
    private volatile int topChunksToTrack = 10;
    private volatile int profilingIntervalSec = 30;

    // State: per-server performance data
    private final ConcurrentHashMap<String, ServerState> serverStates = new ConcurrentHashMap<>();

    // Stats
    private final AtomicLong totalLagSpikes = new AtomicLong(0);
    private final AtomicLong totalWarnings = new AtomicLong(0);

    // History: lag spike events
    private final Deque<LagSpikeEvent> lagSpikeHistory = new ConcurrentLinkedDeque<>();
    private static final int MAX_SPIKE_HISTORY = 100;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        ServiceRegistry reg = ctx.getServiceRegistry();
        this.extenderService = reg.get(ExtenderService.class).orElse(null);
        this.metricsService = reg.get(MetricsService.class).orElse(null);
        this.alertService = reg.get(AlertService.class).orElse(null);
        this.configManager = reg.get(ConfigManager.class).orElse(null);
        this.logManager = reg.get(LogManager.class).orElse(null);

        loadConfig();

        // Subscribe to extender profiling events
        if (extenderService != null) {
            extenderService.subscribeToEvent("server-monitor", "profiling_report", this::handleProfilingReport);
            extenderService.onMessage("server-monitor", "heartbeat", this::handleHeartbeat);
        }

        // Periodic profiling request + analysis
        ctx.getTaskScheduler().scheduleAtFixedRate(this::requestProfiling, 10, profilingIntervalSec, TimeUnit.SECONDS);
        ctx.getTaskScheduler().scheduleAtFixedRate(this::analyzePerformance, 15, 10, TimeUnit.SECONDS);

        registerDashboard();
        log("INFO", "LIFECYCLE", "Server Monitor enabled [tps_warn=" + tpsWarnThreshold +
                ", tps_critical=" + tpsCriticalThreshold + ", profiling_interval=" + profilingIntervalSec + "s]");
    }

    @Override
    public void onDisable() {
        if (extenderService != null) {
            extenderService.removeAllHandlers("server-monitor");
        }
        log("INFO", "LIFECYCLE", "Server Monitor disabled [spikes_detected=" + totalLagSpikes.get() + "]");
    }

    @Override
    public void onReload() {
        loadConfig();
        log("INFO", "LIFECYCLE", "Configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("server-monitor", level, tag, message);
    }

    // ── Config ──

    private void loadConfig() {
        if (configManager == null) return;
        String id = context.getDescriptor().id();
        Map<String, Object> cfg = configManager.getModuleConfig(id);
        if (cfg.isEmpty()) { saveDefaultConfig(); cfg = configManager.getModuleConfig(id); }
        enabled = boolCfg(cfg, "enabled", true);
        tpsWarnThreshold = numCfg(cfg, "tps_warn_threshold", 18.0);
        tpsCriticalThreshold = numCfg(cfg, "tps_critical_threshold", 15.0);
        memoryWarnPercent = (int) numCfg(cfg, "memory_warn_percent", 85);
        lagSpikeThresholdMs = (int) numCfg(cfg, "lag_spike_threshold_ms", 100);
        topChunksToTrack = (int) numCfg(cfg, "top_chunks_to_track", 10);
        profilingIntervalSec = (int) numCfg(cfg, "profiling_interval_sec", 30);
    }

    private void saveDefaultConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", true);
        cfg.put("tps_warn_threshold", 18.0);
        cfg.put("tps_critical_threshold", 15.0);
        cfg.put("memory_warn_percent", 85);
        cfg.put("lag_spike_threshold_ms", 100);
        cfg.put("top_chunks_to_track", 10);
        cfg.put("profiling_interval_sec", 30);
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
    }

    // ── Extender Data Handling ──

    private void handleHeartbeat(ExtenderMessage msg) {
        String server = msg.serverLabel();
        ServerState state = serverStates.computeIfAbsent(server, k -> new ServerState(server));

        try {
            JsonObject data = msg.payload();
            if (data.has("tps")) state.tps = data.get("tps").getAsDouble();
            if (data.has("memory_used_mb")) state.memUsedMb = data.get("memory_used_mb").getAsLong();
            if (data.has("memory_max_mb")) state.memMaxMb = data.get("memory_max_mb").getAsLong();
            if (data.has("cpu_usage")) state.cpuPercent = data.get("cpu_usage").getAsDouble();
            if (data.has("players")) state.playerCount = data.get("players").getAsInt();
            state.lastHeartbeat = System.currentTimeMillis();

            // Record TPS history
            state.tpsHistory.addLast(new double[]{System.currentTimeMillis(), state.tps});
            while (state.tpsHistory.size() > 360) state.tpsHistory.pollFirst(); // 1hr at 10s

            // Publish metrics
            if (metricsService != null) {
                String[] labels = new String[]{server};
                metricsService.gaugeSet("ip_server_tps", "Backend server TPS",
                        new String[]{"server"}, labels, state.tps);
                metricsService.gaugeSet("ip_server_players", "Backend server player count",
                        new String[]{"server"}, labels, state.playerCount);
            }
        } catch (Exception e) { logger.debug("Error processing heartbeat: {}", e.getMessage()); }
    }

    private void handleProfilingReport(ExtenderMessage msg) {
        String server = msg.serverLabel();
        String reportJson = msg.getString("report");
        if (reportJson == null) return;

        ServerState state = serverStates.computeIfAbsent(server, k -> new ServerState(server));

        try {
            JsonObject report = JsonParser.parseString(reportJson).getAsJsonObject();

            // Chunk tick costs
            if (report.has("chunks")) {
                List<ChunkCost> chunks = new ArrayList<>();
                for (JsonElement el : report.getAsJsonArray("chunks")) {
                    JsonObject c = el.getAsJsonObject();
                    chunks.add(new ChunkCost(
                            c.has("world") ? c.get("world").getAsString() : "?",
                            c.has("x") ? c.get("x").getAsInt() : 0,
                            c.has("z") ? c.get("z").getAsInt() : 0,
                            c.has("tick_ms") ? c.get("tick_ms").getAsDouble() : 0,
                            c.has("entity_count") ? c.get("entity_count").getAsInt() : 0,
                            c.has("tile_entity_count") ? c.get("tile_entity_count").getAsInt() : 0,
                            c.has("players_nearby") ? parseStringList(c.getAsJsonArray("players_nearby")) : List.of()
                    ));
                }
                chunks.sort((a, b) -> Double.compare(b.tickMs, a.tickMs));
                state.topChunks = chunks.subList(0, Math.min(topChunksToTrack, chunks.size()));

                // Check for lag spikes
                for (ChunkCost chunk : state.topChunks) {
                    if (chunk.tickMs > lagSpikeThresholdMs) {
                        totalLagSpikes.incrementAndGet();
                        LagSpikeEvent spike = new LagSpikeEvent(server, chunk, Instant.now());
                        lagSpikeHistory.addLast(spike);
                        while (lagSpikeHistory.size() > MAX_SPIKE_HISTORY) lagSpikeHistory.pollFirst();

                        String spikeMsg = String.format("Lag spike: chunk [%d, %d] in %s at %.1fms (entities: %d, players: %s)",
                                chunk.x, chunk.z, chunk.world, chunk.tickMs, chunk.entityCount, chunk.playersNearby);
                        log("WARN", "LAG_SPIKE", spikeMsg);

                        if (alertService != null) {
                            alertService.sendAlert(AlertService.Severity.WARNING, "Lag Spike",
                                    server + ": " + spikeMsg);
                        }

                        if (metricsService != null) {
                            metricsService.counterInc("ip_lag_spikes_total", "Total lag spikes detected",
                                    new String[]{"server"}, new String[]{server});
                        }
                    }
                }
            }

            // Plugin tick times
            if (report.has("plugins")) {
                List<PluginTick> plugins = new ArrayList<>();
                for (JsonElement el : report.getAsJsonArray("plugins")) {
                    JsonObject p = el.getAsJsonObject();
                    plugins.add(new PluginTick(
                            p.has("name") ? p.get("name").getAsString() : "?",
                            p.has("tick_ms") ? p.get("tick_ms").getAsDouble() : 0,
                            p.has("event_count") ? p.get("event_count").getAsInt() : 0
                    ));
                }
                plugins.sort((a, b) -> Double.compare(b.tickMs, a.tickMs));
                state.pluginTicks = plugins;

                // Publish plugin metrics
                if (metricsService != null) {
                    for (PluginTick pt : plugins) {
                        metricsService.gaugeSet("ip_plugin_tick_ms", "Plugin tick time in ms",
                                new String[]{"server", "plugin"}, new String[]{server, pt.name}, pt.tickMs);
                    }
                }
            }

            state.lastProfilingReport = System.currentTimeMillis();
            log("DEBUG", "PROFILING", "Received profiling report from " + server +
                    " (chunks=" + state.topChunks.size() + ", plugins=" + state.pluginTicks.size() + ")");

        } catch (Exception e) {
            log("WARN", "PROFILING", "Failed to parse profiling report from " + server + ": " + e.getMessage());
        }
    }

    private List<String> parseStringList(JsonArray arr) {
        List<String> list = new ArrayList<>();
        if (arr != null) arr.forEach(el -> list.add(el.getAsString()));
        return list;
    }

    // ── Periodic Tasks ──

    private void requestProfiling() {
        if (!enabled || extenderService == null) return;
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", "profile");
        cmd.put("top_chunks", topChunksToTrack);
        cmd.put("lag_threshold_ms", lagSpikeThresholdMs);
        extenderService.broadcastCommand("server-monitor", cmd);
    }

    private void analyzePerformance() {
        if (!enabled) return;
        for (ServerState state : serverStates.values()) {
            // TPS warnings
            if (state.tps > 0 && state.tps < tpsCriticalThreshold) {
                totalWarnings.incrementAndGet();
                log("WARN", "TPS_CRITICAL", state.server + " TPS critically low: " + String.format("%.1f", state.tps));
                if (alertService != null) {
                    alertService.sendAlert(AlertService.Severity.CRITICAL, "TPS Critical",
                            state.server + " TPS at " + String.format("%.1f", state.tps));
                }
            } else if (state.tps > 0 && state.tps < tpsWarnThreshold) {
                log("INFO", "TPS_WARN", state.server + " TPS warning: " + String.format("%.1f", state.tps));
            }

            // Memory warnings
            if (state.memMaxMb > 0) {
                double memPercent = (state.memUsedMb * 100.0) / state.memMaxMb;
                if (memPercent > memoryWarnPercent) {
                    totalWarnings.incrementAndGet();
                    log("WARN", "MEMORY", state.server + " memory at " + String.format("%.0f%%", memPercent));
                    if (alertService != null) {
                        alertService.sendAlert(AlertService.Severity.WARNING, "High Memory",
                                state.server + " at " + String.format("%.0f%%", memPercent) +
                                        " (" + state.memUsedMb + "/" + state.memMaxMb + " MB)");
                    }
                }
            }
        }
    }

    // ── Dashboard ──

    private void registerDashboard() {
        ModuleDashboard d = context.getDashboard();
        d.get("status", this::apiStatus);
        d.get("servers", this::apiServers);
        d.get("server/{name}", this::apiServerDetail);
        d.get("lag-spikes", this::apiLagSpikes);
        d.get("config", this::apiGetConfig);
        d.post("config", this::apiSaveConfig);
    }

    private void apiStatus(RequestContext ctx) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", enabled);
        s.put("server_count", serverStates.size());
        s.put("total_lag_spikes", totalLagSpikes.get());
        s.put("total_warnings", totalWarnings.get());
        s.put("profiling_interval_sec", profilingIntervalSec);

        // Aggregate stats
        double avgTps = serverStates.values().stream()
                .filter(st -> st.tps > 0)
                .mapToDouble(st -> st.tps).average().orElse(0);
        s.put("avg_tps", Math.round(avgTps * 10.0) / 10.0);
        s.put("total_players", serverStates.values().stream().mapToInt(st -> st.playerCount).sum());
        ctx.json(s);
    }

    private void apiServers(RequestContext ctx) {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (ServerState st : serverStates.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", st.server);
            info.put("tps", Math.round(st.tps * 10.0) / 10.0);
            info.put("memory_used_mb", st.memUsedMb);
            info.put("memory_max_mb", st.memMaxMb);
            info.put("cpu_percent", Math.round(st.cpuPercent * 10.0) / 10.0);
            info.put("players", st.playerCount);
            info.put("last_heartbeat", st.lastHeartbeat);
            info.put("top_chunk_cost_ms", st.topChunks.isEmpty() ? 0 :
                    Math.round(st.topChunks.get(0).tickMs * 10.0) / 10.0);
            info.put("top_plugin_cost_ms", st.pluginTicks.isEmpty() ? 0 :
                    Math.round(st.pluginTicks.get(0).tickMs * 10.0) / 10.0);
            servers.add(info);
        }
        ctx.json(Map.of("servers", servers));
    }

    private void apiServerDetail(RequestContext ctx) {
        String name = ctx.pathParam("name");
        ServerState state = serverStates.get(name);
        if (state == null) { ctx.status(404).json(Map.of("error", "Server not found")); return; }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", state.server);
        detail.put("tps", state.tps);
        detail.put("memory_used_mb", state.memUsedMb);
        detail.put("memory_max_mb", state.memMaxMb);
        detail.put("cpu_percent", state.cpuPercent);
        detail.put("players", state.playerCount);

        // TPS history
        List<List<Object>> tpsHist = new ArrayList<>();
        for (double[] pt : state.tpsHistory) {
            tpsHist.add(List.of((long) pt[0], Math.round(pt[1] * 10.0) / 10.0));
        }
        detail.put("tps_history", tpsHist);

        // Top chunks
        List<Map<String, Object>> chunks = new ArrayList<>();
        for (ChunkCost c : state.topChunks) {
            Map<String, Object> ci = new LinkedHashMap<>();
            ci.put("world", c.world); ci.put("x", c.x); ci.put("z", c.z);
            ci.put("tick_ms", Math.round(c.tickMs * 10.0) / 10.0);
            ci.put("entity_count", c.entityCount);
            ci.put("tile_entity_count", c.tileEntityCount);
            ci.put("players_nearby", c.playersNearby);
            ci.put("is_spike", c.tickMs > lagSpikeThresholdMs);
            chunks.add(ci);
        }
        detail.put("top_chunks", chunks);

        // Plugin tick times
        List<Map<String, Object>> plugins = new ArrayList<>();
        for (PluginTick pt : state.pluginTicks) {
            Map<String, Object> pi = new LinkedHashMap<>();
            pi.put("name", pt.name);
            pi.put("tick_ms", Math.round(pt.tickMs * 10.0) / 10.0);
            pi.put("event_count", pt.eventCount);
            plugins.add(pi);
        }
        detail.put("plugins", plugins);
        detail.put("last_profiling_report", state.lastProfilingReport);

        ctx.json(detail);
    }

    private void apiLagSpikes(RequestContext ctx) {
        List<Map<String, Object>> spikes = new ArrayList<>();
        for (LagSpikeEvent spike : lagSpikeHistory) {
            Map<String, Object> si = new LinkedHashMap<>();
            si.put("server", spike.server);
            si.put("world", spike.chunk.world);
            si.put("x", spike.chunk.x); si.put("z", spike.chunk.z);
            si.put("tick_ms", spike.chunk.tickMs);
            si.put("entity_count", spike.chunk.entityCount);
            si.put("players_nearby", spike.chunk.playersNearby);
            si.put("timestamp", spike.timestamp.toString());
            spikes.add(si);
        }
        ctx.json(Map.of("total", totalLagSpikes.get(), "recent", spikes));
    }

    private void apiGetConfig(RequestContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", enabled);
        c.put("tps_warn_threshold", tpsWarnThreshold);
        c.put("tps_critical_threshold", tpsCriticalThreshold);
        c.put("memory_warn_percent", memoryWarnPercent);
        c.put("lag_spike_threshold_ms", lagSpikeThresholdMs);
        c.put("top_chunks_to_track", topChunksToTrack);
        c.put("profiling_interval_sec", profilingIntervalSec);
        ctx.json(c);
    }

    private void apiSaveConfig(RequestContext ctx) {
        if (configManager == null) { ctx.status(500).json(Map.of("error", "Config unavailable")); return; }
        JsonObject b = gson.fromJson(ctx.body(), JsonObject.class);
        Map<String, Object> cfg = configManager.getModuleConfig(context.getDescriptor().id());
        b.entrySet().forEach(e -> {
            if (e.getValue().isJsonPrimitive()) {
                var p = e.getValue().getAsJsonPrimitive();
                if (p.isBoolean()) cfg.put(e.getKey(), p.getAsBoolean());
                else if (p.isNumber()) cfg.put(e.getKey(), p.getAsNumber());
                else cfg.put(e.getKey(), p.getAsString());
            }
        });
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
        loadConfig();
        log("INFO", "CONFIG", "Configuration updated via dashboard");
        ctx.json(Map.of("success", true));
    }

    // ── Data Classes ──

    static class ServerState {
        final String server;
        volatile double tps = 0;
        volatile long memUsedMb = 0;
        volatile long memMaxMb = 0;
        volatile double cpuPercent = 0;
        volatile int playerCount = 0;
        volatile long lastHeartbeat = 0;
        volatile long lastProfilingReport = 0;
        final Deque<double[]> tpsHistory = new ConcurrentLinkedDeque<>();
        volatile List<ChunkCost> topChunks = List.of();
        volatile List<PluginTick> pluginTicks = List.of();

        ServerState(String server) { this.server = server; }
    }

    record ChunkCost(String world, int x, int z, double tickMs,
                     int entityCount, int tileEntityCount, List<String> playersNearby) {}

    record PluginTick(String name, double tickMs, int eventCount) {}

    record LagSpikeEvent(String server, ChunkCost chunk, Instant timestamp) {}

    // ── Helpers ──

    private boolean boolCfg(Map<String, Object> m, String k, boolean d) {
        Object v = m.get(k); return v instanceof Boolean b ? b : d;
    }
    private double numCfg(Map<String, Object> m, String k, double d) {
        Object v = m.get(k); return v instanceof Number n ? n.doubleValue() : d;
    }
}

