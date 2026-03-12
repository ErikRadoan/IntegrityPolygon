package dev.erikradovan.profiler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.erikradovan.integritypolygon.api.AlertService;
import dev.erikradovan.integritypolygon.api.ExtenderMessage;
import dev.erikradovan.integritypolygon.api.ExtenderService;
import dev.erikradovan.integritypolygon.api.ModuleContext;
import dev.erikradovan.integritypolygon.api.ModuleDashboard;
import dev.erikradovan.integritypolygon.api.ModuleDashboard.RequestContext;
import dev.erikradovan.integritypolygon.api.ServiceRegistry;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.logging.LogManager;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Profiler Module — deep JVM profiling for Paper backend servers.
 *
 * <p>On enable, this module deploys a companion extender module
 * ({@code profiler-extender.jar}) to all connected Paper servers.
 * The extender module performs JVM-level profiling (thread sampling,
 * TPS/MSPT, CPU, memory, GC) and sends periodic reports back over
 * the TCP tunnel.
 *
 * <h3>Profiling capabilities</h3>
 * <ul>
 *   <li>Thread &amp; stack trace sampling with per-plugin attribution</li>
 *   <li>TPS &amp; MSPT (mean, min, max, p95) over rolling windows</li>
 *   <li>System + process CPU; per-plugin CPU usage</li>
 *   <li>Heap memory, GC collection count, GC pause times</li>
 *   <li>Flame graph data (parent→child call chains)</li>
 * </ul>
 */
public class ProfilerModule implements dev.erikradovan.integritypolygon.api.Module {

    private ModuleContext context;
    private Logger logger;
    private final Gson gson = new Gson();

    private ExtenderService extenderService;
    private ConfigManager configManager;
    private LogManager logManager;
    private AlertService alertService;

    // Per-server profiling state
    private final ConcurrentHashMap<String, ServerProfile> serverProfiles = new ConcurrentHashMap<>();

    // Config
    private volatile int reportIntervalSec = 10;
    private volatile int sampleIntervalMs = 1;
    private volatile boolean enabled = true;

    private final AtomicBoolean extenderDeployed = new AtomicBoolean(false);

    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        ServiceRegistry reg = ctx.getServiceRegistry();
        this.extenderService = reg.get(ExtenderService.class).orElse(null);
        this.configManager = reg.get(ConfigManager.class).orElse(null);
        this.alertService = reg.get(AlertService.class).orElse(null);
        this.logManager = reg.get(LogManager.class).orElse(null);

        loadConfig();

        // Deploy the extender module
        deployExtenderModule();

        // Register handlers for incoming profiling data
        if (extenderService != null) {
            extenderService.onMessage("profiler", "profiling_data", this::handleProfilingData);
            extenderService.onMessage("profiler", "flame_data", this::handleFlameData);
            extenderService.onMessage("profiler", "ready", this::handleProfilerReady);
        }

        ctx.getTaskScheduler().schedule(this::sendConfigToExtenders, 2, TimeUnit.SECONDS);

        registerDashboard();
        log("INFO", "LIFECYCLE", "Profiler enabled [interval=" + reportIntervalSec + "s, sample=" + sampleIntervalMs + "ms]");
    }

    @Override
    public void onDisable() {
        if (extenderService != null) {
            extenderService.removeAllHandlers("profiler");
            if (extenderDeployed.get()) {
                extenderService.undeployExtenderModule("profiler");
            }
        }
        log("INFO", "LIFECYCLE", "Profiler disabled");
    }

    @Override
    public void onReload() {
        loadConfig();
        // Push updated config to extenders
        sendConfigToExtenders();
        log("INFO", "LIFECYCLE", "Profiler configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("profiler", level, tag, message);
    }

    // ── Extender Module Deployment ──────────────────────────────

    private void deployExtenderModule() {
        if (extenderService == null) {
            logger.warn("ExtenderService unavailable — cannot deploy profiler extender module");
            return;
        }

        try {
            // Read the bundled extender JAR from our own resources
            byte[] jarBytes = loadBundledExtenderJar();
            if (jarBytes == null || jarBytes.length == 0) {
                logger.warn("profiler-extender.jar not found in resources — " +
                        "profiling will not be available on backend servers");
                return;
            }

            String version = context.getDescriptor().version();
            extenderService.deployExtenderModule("profiler", jarBytes, version);
            extenderDeployed.set(true);
            logger.info("Deployed profiler extender module ({} bytes) to all backend servers", jarBytes.length);
            log("INFO", "DEPLOY", "Deployed profiler-extender.jar (" + jarBytes.length + " bytes)");

        } catch (Exception e) {
            logger.error("Failed to deploy profiler extender module: {}", e.getMessage());
            log("ERROR", "DEPLOY", "Failed to deploy: " + e.getMessage());
        }
    }

    private byte[] loadBundledExtenderJar() {
        // The JAR should be bundled at /profiler-extender.jar in this module's resources
        try (InputStream is = getClass().getResourceAsStream("/profiler-extender.jar")) {
            if (is == null) return null;
            return is.readAllBytes();
        } catch (IOException e) {
            logger.error("Could not read bundled profiler-extender.jar: {}", e.getMessage());
            return null;
        }
    }

    private void sendConfigToExtenders() {
        if (extenderService == null) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "configure");
        payload.addProperty("sample_interval_ms", sampleIntervalMs);
        payload.addProperty("report_interval_sec", reportIntervalSec);
        extenderService.broadcastMessage("profiler", "command", payload);
    }

    private void sendConfigToExtender(String extenderId) {
        if (extenderService == null || extenderId == null || extenderId.isBlank()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("action", "configure");
        payload.addProperty("sample_interval_ms", sampleIntervalMs);
        payload.addProperty("report_interval_sec", reportIntervalSec);
        extenderService.sendMessage("profiler", extenderId, "command", payload);
    }

    // ── Config ──────────────────────────────────────────────────

    private void loadConfig() {
        if (configManager == null) return;
        String id = context.getDescriptor().id();
        Map<String, Object> cfg = configManager.getModuleConfig(id);
        if (cfg.isEmpty()) { saveDefaultConfig(); cfg = configManager.getModuleConfig(id); }
        enabled = boolCfg(cfg, "enabled", true);
        reportIntervalSec = intCfg(cfg, "report_interval_sec", 10);
        sampleIntervalMs = intCfg(cfg, "sample_interval_ms", 1);
    }

    private void saveDefaultConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", true);
        cfg.put("report_interval_sec", 10);
        cfg.put("sample_interval_ms", 1);
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
    }

    private void handleProfilerReady(ExtenderMessage msg) {
        log("INFO", "EXTENDER", "Profiler extender ready on " + msg.serverLabel());
        sendConfigToExtender(msg.source());
    }

    // ── Incoming Data Handlers ──────────────────────────────────

    private void handleProfilingData(ExtenderMessage msg) {
        String server = msg.serverLabel();
        ServerProfile profile = serverProfiles.computeIfAbsent(server, k -> new ServerProfile(server));
        JsonObject data = msg.payload();

        try {
            // TPS / MSPT
            if (data.has("tps_10s")) profile.tps10s = data.get("tps_10s").getAsDouble();
            if (data.has("tps_1m")) profile.tps1m = data.get("tps_1m").getAsDouble();
            if (data.has("tps_5m")) profile.tps5m = data.get("tps_5m").getAsDouble();
            if (data.has("mspt_mean")) profile.msptMean = data.get("mspt_mean").getAsDouble();
            if (data.has("mspt_min")) profile.msptMin = data.get("mspt_min").getAsDouble();
            if (data.has("mspt_max")) profile.msptMax = data.get("mspt_max").getAsDouble();
            if (data.has("mspt_p95")) profile.msptP95 = data.get("mspt_p95").getAsDouble();

            // CPU
            if (data.has("system_cpu")) profile.systemCpu = data.get("system_cpu").getAsDouble();
            if (data.has("process_cpu")) profile.processCpu = data.get("process_cpu").getAsDouble();

            // Memory
            if (data.has("heap_used_mb")) profile.heapUsedMb = data.get("heap_used_mb").getAsLong();
            if (data.has("heap_max_mb")) profile.heapMaxMb = data.get("heap_max_mb").getAsLong();

            // GC
            if (data.has("gc_collections")) profile.gcCollections = data.get("gc_collections").getAsLong();
            if (data.has("gc_time_ms")) profile.gcTimeMs = data.get("gc_time_ms").getAsLong();
            if (data.has("gc_frequency_per_min")) profile.gcFrequencyPerMin = data.get("gc_frequency_per_min").getAsDouble();
            if (data.has("gc_avg_pause_ms")) profile.gcAvgPauseMs = data.get("gc_avg_pause_ms").getAsDouble();

            if (data.has("gc_collectors")) {
                List<Map<String, Object>> collectors = new ArrayList<>();
                for (JsonElement el : data.getAsJsonArray("gc_collectors")) {
                    JsonObject gc = el.getAsJsonObject();
                    Map<String, Object> collector = new LinkedHashMap<>();
                    collector.put("name", gc.has("name") ? gc.get("name").getAsString() : "?");
                    collector.put("collections", gc.has("collections") ? gc.get("collections").getAsLong() : 0L);
                    collector.put("collection_time_ms", gc.has("collection_time_ms") ? gc.get("collection_time_ms").getAsLong() : 0L);
                    collector.put("delta_collections", gc.has("delta_collections") ? gc.get("delta_collections").getAsLong() : 0L);
                    collector.put("delta_time_ms", gc.has("delta_time_ms") ? gc.get("delta_time_ms").getAsLong() : 0L);
                    collector.put("frequency_per_min", gc.has("frequency_per_min") ? gc.get("frequency_per_min").getAsDouble() : 0.0);
                    collector.put("avg_pause_ms", gc.has("avg_pause_ms") ? gc.get("avg_pause_ms").getAsDouble() : 0.0);
                    collectors.add(collector);
                }
                profile.gcCollectors = collectors;
            }

            if (data.has("sample_interval_ms")) profile.sampleIntervalMs = data.get("sample_interval_ms").getAsInt();
            if (data.has("report_interval_sec")) profile.reportIntervalSec = data.get("report_interval_sec").getAsInt();
            if (data.has("cumulative_samples")) profile.cumulativeSamples = data.get("cumulative_samples").getAsLong();

            // Top methods (from stack sampling)
            if (data.has("top_methods")) {
                List<Map<String, Object>> methods = new ArrayList<>();
                for (JsonElement el : data.getAsJsonArray("top_methods")) {
                    JsonObject m = el.getAsJsonObject();
                    Map<String, Object> method = new LinkedHashMap<>();
                    method.put("method", m.has("method") ? m.get("method").getAsString() : "?");
                    method.put("plugin", m.has("plugin") ? m.get("plugin").getAsString() : "?");
                    method.put("samples", m.has("samples") ? m.get("samples").getAsLong() : 0);
                    method.put("percent", m.has("percent") ? m.get("percent").getAsDouble() : 0.0);
                    methods.add(method);
                }
                profile.topMethods = methods;
            }

            // Per-plugin CPU
            if (data.has("plugin_cpu")) {
                Map<String, Double> pluginCpu = new LinkedHashMap<>();
                JsonObject pc = data.getAsJsonObject("plugin_cpu");
                for (String key : pc.keySet()) {
                    pluginCpu.put(key, pc.get(key).getAsDouble());
                }
                profile.pluginCpu = pluginCpu;
            }

            // Per-plugin samples
            if (data.has("plugin_samples")) {
                Map<String, Long> pluginSamples = new LinkedHashMap<>();
                JsonObject ps = data.getAsJsonObject("plugin_samples");
                for (String key : ps.keySet()) {
                    pluginSamples.put(key, ps.get(key).getAsLong());
                }
                profile.pluginSamples = pluginSamples;
            }

            profile.lastUpdate = System.currentTimeMillis();
            profile.totalSamples = data.has("total_samples") ? data.get("total_samples").getAsLong() : 0;

        } catch (Exception e) {
            logger.debug("Error processing profiling data from {}: {}", server, e.getMessage());
        }
    }

    private void handleFlameData(ExtenderMessage msg) {
        String server = msg.serverLabel();
        ServerProfile profile = serverProfiles.computeIfAbsent(server, k -> new ServerProfile(server));
        JsonObject data = msg.payload();

        if (data.has("flame_graph")) {
            profile.flameGraphJson = data.get("flame_graph").toString();
            profile.lastFlameGraphUpdate = System.currentTimeMillis();
        }
    }

    // ── Dashboard API ───────────────────────────────────────────

    private void registerDashboard() {
        ModuleDashboard d = context.getDashboard();
        d.get("status", this::apiStatus);
        d.get("servers", this::apiServers);
        d.get("server/{name}", this::apiServerDetail);
        d.get("server/{name}/flamegraph", this::apiFlameGraph);
        d.get("config", this::apiGetConfig);
        d.post("config", this::apiSaveConfig);
        d.post("request-flame/{name}", this::apiRequestFlame);
    }

    private void apiStatus(RequestContext ctx) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", enabled);
        s.put("server_count", serverProfiles.size());
        s.put("report_interval_sec", reportIntervalSec);
        s.put("sample_interval_ms", sampleIntervalMs);
        s.put("extender_deployed", extenderDeployed.get());
        s.put("connected_servers", serverProfiles.size());
        ctx.json(s);
    }

    private void apiServers(RequestContext ctx) {
        List<Map<String, Object>> servers = new ArrayList<>();
        for (ServerProfile p : serverProfiles.values()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", p.server);
            info.put("tps_10s", round(p.tps10s));
            info.put("tps_1m", round(p.tps1m));
            info.put("tps_5m", round(p.tps5m));
            info.put("mspt_mean", round(p.msptMean));
            info.put("mspt_p95", round(p.msptP95));
            info.put("system_cpu", round(p.systemCpu));
            info.put("process_cpu", round(p.processCpu));
            info.put("heap_used_mb", p.heapUsedMb);
            info.put("heap_max_mb", p.heapMaxMb);
            info.put("sample_interval_ms", p.sampleIntervalMs);
            info.put("report_interval_sec", p.reportIntervalSec);
            info.put("last_update", p.lastUpdate);
            servers.add(info);
        }
        ctx.json(Map.of("servers", servers));
    }

    private void apiServerDetail(RequestContext ctx) {
        String name = ctx.pathParam("name");
        ServerProfile p = serverProfiles.get(name);
        if (p == null) { ctx.status(404).json(Map.of("error", "Server not found")); return; }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", p.server);
        detail.put("tps_10s", round(p.tps10s));
        detail.put("tps_1m", round(p.tps1m));
        detail.put("tps_5m", round(p.tps5m));
        detail.put("mspt_mean", round(p.msptMean));
        detail.put("mspt_min", round(p.msptMin));
        detail.put("mspt_max", round(p.msptMax));
        detail.put("mspt_p95", round(p.msptP95));
        detail.put("system_cpu", round(p.systemCpu));
        detail.put("process_cpu", round(p.processCpu));
        detail.put("heap_used_mb", p.heapUsedMb);
        detail.put("heap_max_mb", p.heapMaxMb);
        detail.put("gc_collections", p.gcCollections);
        detail.put("gc_time_ms", p.gcTimeMs);
        detail.put("gc_frequency_per_min", round(p.gcFrequencyPerMin));
        detail.put("gc_avg_pause_ms", round(p.gcAvgPauseMs));
        detail.put("gc_collectors", p.gcCollectors != null ? p.gcCollectors : List.of());
        detail.put("total_samples", p.totalSamples);
        detail.put("cumulative_samples", p.cumulativeSamples);
        detail.put("top_methods", p.topMethods != null ? p.topMethods : List.of());
        detail.put("plugin_cpu", p.pluginCpu != null ? p.pluginCpu : Map.of());
        detail.put("plugin_samples", p.pluginSamples != null ? p.pluginSamples : Map.of());
        detail.put("sample_interval_ms", p.sampleIntervalMs);
        detail.put("report_interval_sec", p.reportIntervalSec);
        detail.put("flame_graph_available", p.flameGraphJson != null);
        detail.put("last_flame_graph_update", p.lastFlameGraphUpdate);
        detail.put("last_update", p.lastUpdate);
        ctx.json(detail);
    }

    private void apiFlameGraph(RequestContext ctx) {
        String name = ctx.pathParam("name");
        ServerProfile p = serverProfiles.get(name);
        if (p == null || p.flameGraphJson == null) {
            ctx.status(404).json(Map.of("error", "No flame graph data"));
            return;
        }
        ctx.json(JsonParser.parseString(p.flameGraphJson));
    }

    private void apiRequestFlame(RequestContext ctx) {
        String name = ctx.pathParam("name");
        if (extenderService == null) { ctx.status(500).json(Map.of("error", "No extender service")); return; }

        String extenderId = findExtenderIdByServer(name);
        if (extenderId == null) {
            ctx.status(404).json(Map.of("error", "No extender found for server " + name));
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("action", "generate_flame");
        payload.addProperty("server", name);
        extenderService.sendMessage("profiler", extenderId, "command", payload);
        ctx.json(Map.of("success", true, "message", "Flame graph requested", "extender", extenderId));
    }

    private String findExtenderIdByServer(String serverName) {
        if (extenderService == null || serverName == null) return null;
        for (Map.Entry<String, Map<String, Object>> entry : extenderService.getServerStates().entrySet()) {
            Object server = entry.getValue().get("server");
            if (server != null && serverName.equalsIgnoreCase(String.valueOf(server))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void apiGetConfig(RequestContext ctx) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", enabled);
        c.put("report_interval_sec", reportIntervalSec);
        c.put("sample_interval_ms", sampleIntervalMs);
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
        sendConfigToExtenders();
        log("INFO", "CONFIG", "Configuration updated via dashboard");
        ctx.json(Map.of("success", true));
    }

    // ── Data classes ────────────────────────────────────────────

    static class ServerProfile {
        final String server;
        volatile double tps10s, tps1m, tps5m;
        volatile double msptMean, msptMin, msptMax, msptP95;
        volatile double systemCpu, processCpu;
        volatile long heapUsedMb, heapMaxMb;
        volatile long gcCollections, gcTimeMs;
        volatile double gcFrequencyPerMin, gcAvgPauseMs;
        volatile long totalSamples;
        volatile long cumulativeSamples;
        volatile List<Map<String, Object>> topMethods;
        volatile Map<String, Double> pluginCpu;
        volatile Map<String, Long> pluginSamples;
        volatile List<Map<String, Object>> gcCollectors;
        volatile String flameGraphJson;
        volatile int sampleIntervalMs;
        volatile int reportIntervalSec;
        volatile long lastUpdate;
        volatile long lastFlameGraphUpdate;

        ServerProfile(String server) { this.server = server; }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }
    private boolean boolCfg(Map<String, Object> m, String k, boolean d) {
        Object v = m.get(k); return v instanceof Boolean b ? b : d;
    }
    private int intCfg(Map<String, Object> m, String k, int d) {
        Object v = m.get(k); return v instanceof Number n ? n.intValue() : d;
    }
}

