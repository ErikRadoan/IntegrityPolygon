package dev.erikradovan.profiler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Base64;
import java.util.*;
import java.util.concurrent.*;

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

    // Per-server profiling state
    private final ConcurrentHashMap<String, ServerProfile> serverProfiles = new ConcurrentHashMap<>();

    // Config
    private volatile int reportIntervalSec = 10;
    private volatile int sampleIntervalMs = 1;
    private volatile boolean enabled = true;
    private volatile int historyMaxPoints = 180;

    private volatile byte[] bundledExtenderJar;
    private final Set<String> extendedExtenders = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Deque<MetricSample>> historyByServer = new ConcurrentHashMap<>();
    private static final int MAX_HISTORY_POINTS_CAP = 2000;
    private static final long HISTORY_FLUSH_INTERVAL_SEC = 15;
    private volatile Path historyDbFile;
    private volatile boolean historyDirty;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;
        this.logger = ctx.getLogger();
        ServiceRegistry reg = ctx.getServiceRegistry();
        this.extenderService = reg.get(ExtenderService.class).orElse(null);
        this.configManager = reg.get(ConfigManager.class).orElse(null);
        this.logManager = reg.get(LogManager.class).orElse(null);

        loadConfig();
        historyDbFile = ctx.getDataDirectory().resolve("history-db.json");
        loadHistoryDatabase();
        ctx.getTaskScheduler().scheduleAtFixedRate(this::flushHistoryIfDirty, HISTORY_FLUSH_INTERVAL_SEC, HISTORY_FLUSH_INTERVAL_SEC, TimeUnit.SECONDS);

        // Load bundled extender JAR for on-demand per-server deployment.
        bundledExtenderJar = loadBundledExtenderJar();
        if (bundledExtenderJar == null || bundledExtenderJar.length == 0) {
            logger.warn("profiler-extender.jar not found in resources — manual extension will be unavailable");
        }

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
        flushHistoryDatabase();
        if (extenderService != null) {
            extenderService.removeAllHandlers("profiler");
        }
        log("INFO", "LIFECYCLE", "Profiler disabled");
    }

    @Override
    public void onReload() {
        loadConfig();
        trimAllHistoryToRetention();
        // Push updated config to extenders
        sendConfigToExtenders();
        log("INFO", "LIFECYCLE", "Profiler configuration reloaded");
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) logManager.log("profiler", level, tag, message);
    }

    // ── Extender Module Deployment ──────────────────────────────

    private byte[] loadBundledExtenderJar() {
        // The profiler paper utility module is bundled under utility-classes/server-monitor.
        InputStream stream = getClass().getResourceAsStream("/utility-classes/server-monitor/profiler-extender.jar");
        if (stream == null) {
            // Legacy fallback for older builds.
            stream = getClass().getResourceAsStream("/profiler-extender.jar");
        }
        try (InputStream is = stream) {
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
        historyMaxPoints = Math.max(20, Math.min(intCfg(cfg, "history_max_points", 180), MAX_HISTORY_POINTS_CAP));
        extendedExtenders.clear();
        Object deployed = cfg.get("deployed_extenders");
        if (deployed instanceof List<?> list) {
            for (Object entry : list) {
                if (entry != null) {
                    String extenderId = String.valueOf(entry).trim();
                    if (!extenderId.isBlank()) {
                        extendedExtenders.add(extenderId);
                    }
                }
            }
        }
    }

    private void saveDefaultConfig() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", true);
        cfg.put("report_interval_sec", 10);
        cfg.put("sample_interval_ms", 1);
        cfg.put("history_max_points", 180);
        cfg.put("deployed_extenders", new ArrayList<String>());
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
    }

    private void handleProfilerReady(ExtenderMessage msg) {
        if (extendedExtenders.add(msg.source())) {
            saveDeploymentState();
        }
        log("INFO", "EXTENDER", "Profiler extender ready on " + extractServerName(msg));
        sendConfigToExtender(msg.source());
    }

    private void sendDeployToExtender(String extenderId) {
        if (extenderService == null || extenderId == null || extenderId.isBlank()) return;
        if (bundledExtenderJar == null || bundledExtenderJar.length == 0) return;

        JsonObject payload = new JsonObject();
        payload.addProperty("module_id", "profiler");
        payload.addProperty("jar_data", Base64.getEncoder().encodeToString(bundledExtenderJar));
        payload.addProperty("version", context.getDescriptor().version());
        payload.addProperty("deploy_path", "utility-classes/server-monitor");
        extenderService.sendMessage("system", extenderId, "deploy_module", payload);
        if (extendedExtenders.add(extenderId)) {
            saveDeploymentState();
        }
    }

    private void sendUndeployToExtender(String extenderId) {
        if (extenderService == null || extenderId == null || extenderId.isBlank()) return;
        JsonObject payload = new JsonObject();
        payload.addProperty("module_id", "profiler");
        payload.addProperty("deploy_path", "utility-classes/server-monitor");
        extenderService.sendMessage("system", extenderId, "undeploy_module", payload);
        if (extendedExtenders.remove(extenderId)) {
            saveDeploymentState();
        }
    }

    // ── Incoming Data Handlers ──────────────────────────────────

    private void handleProfilingData(ExtenderMessage msg) {
        if (extendedExtenders.add(msg.source())) {
            saveDeploymentState();
        }
        String server = extractServerName(msg);
        logger.debug("Received profiling_data from {} ({})", server, msg.source());
        ServerProfile profile = serverProfiles.computeIfAbsent(server, k -> new ServerProfile(server));
        profile.extenderId = msg.source();
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

            if (data.has("sample_tree")) {
                profile.sampleTreeJson = data.get("sample_tree").toString();
            }

            if (data.has("hot_method_tree")) {
                profile.hotMethodTreeJson = data.get("hot_method_tree").toString();
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

            double cpuSample = profile.processCpu > 0 ? profile.processCpu : profile.systemCpu;
            if (!Double.isFinite(cpuSample) || cpuSample < 0) cpuSample = 0;
            appendHistorySample(server, profile.lastUpdate, profile.tps10s, cpuSample);

        } catch (Exception e) {
            logger.debug("Error processing profiling data from {}: {}", server, e.getMessage());
        }
    }

    private void handleFlameData(ExtenderMessage msg) {
        if (extendedExtenders.add(msg.source())) {
            saveDeploymentState();
        }
        String server = extractServerName(msg);
        ServerProfile profile = serverProfiles.computeIfAbsent(server, k -> new ServerProfile(server));
        profile.extenderId = msg.source();
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
        d.get("targets", this::apiTargets);
        d.get("servers", this::apiServers);
        d.get("server/{name}", this::apiServerDetail);
        d.get("config", this::apiGetConfig);
        d.post("config", this::apiSaveConfig);
        d.post("extend/{name}", this::apiExtendServer);
        d.post("unextend/{name}", this::apiUnextendServer);
    }

    private void apiStatus(RequestContext ctx) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("enabled", enabled);
        s.put("server_count", serverProfiles.size());
        s.put("report_interval_sec", reportIntervalSec);
        s.put("sample_interval_ms", sampleIntervalMs);
        s.put("extended_count", extendedExtenders.size());
        s.put("connected_servers", serverProfiles.size());
        ctx.json(s);
    }

    private void apiTargets(RequestContext ctx) {
        if (extenderService == null) {
            ctx.json(Map.of("servers", List.of()));
            return;
        }

        List<Map<String, Object>> targets = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> entry : extenderService.getServerStates().entrySet()) {
            String extenderId = entry.getKey();
            Map<String, Object> state = entry.getValue();
            String stateServer = String.valueOf(state.getOrDefault("server", extenderId));
            ServerProfile profile = findProfileByExtenderId(extenderId);
            String server = profile != null ? profile.server : stateServer;
            String serverIp = String.valueOf(state.getOrDefault("server_ip", ""));
            int serverPort = intFrom(state.getOrDefault("server_port", 0));

            Map<String, Object> row = new LinkedHashMap<>();
            boolean hasHistory = historyByServer.containsKey(server);
            row.put("name", server);
            row.put("server", server);
            row.put("state_server", stateServer);
            row.put("extender_id", extenderId);
            row.put("server_ip", serverIp);
            row.put("server_port", serverPort);
            row.put("identifier", buildIdentifier(extenderId, serverIp, serverPort));
            row.put("extended", extendedExtenders.contains(extenderId) || profile != null);
            row.put("has_data", profile != null || hasHistory);
            row.put("last_heartbeat", state.getOrDefault("last_heartbeat", 0L));
            row.put("players", state.getOrDefault("players", 0));
            row.put("tps", state.getOrDefault("tps", 0.0));
            targets.add(row);
        }

        targets.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        ctx.json(Map.of("servers", targets));
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
            info.put("players", resolvePlayersForServer(p.server));
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
        if (p == null && !historyByServer.containsKey(name)) {
            ctx.status(404).json(Map.of("error", "Server not found"));
            return;
        }
        if (p == null) {
            p = new ServerProfile(name);
        }

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
        detail.put("players", resolvePlayersForServer(p.server));
        detail.put("gc_collections", p.gcCollections);
        detail.put("gc_time_ms", p.gcTimeMs);
        detail.put("gc_frequency_per_min", round(p.gcFrequencyPerMin));
        detail.put("gc_avg_pause_ms", round(p.gcAvgPauseMs));
        detail.put("gc_collectors", p.gcCollectors != null ? p.gcCollectors : List.of());
        detail.put("total_samples", p.totalSamples);
        detail.put("cumulative_samples", p.cumulativeSamples);
        detail.put("top_methods", p.topMethods != null ? p.topMethods : List.of());
        detail.put("sample_tree", parseJsonObject(p.sampleTreeJson));
        detail.put("hot_method_tree", parseJsonObject(p.hotMethodTreeJson));
        detail.put("plugin_cpu", p.pluginCpu != null ? p.pluginCpu : Map.of());
        detail.put("plugin_samples", p.pluginSamples != null ? p.pluginSamples : Map.of());
        detail.put("sample_interval_ms", p.sampleIntervalMs);
        detail.put("report_interval_sec", p.reportIntervalSec);
        detail.put("last_update", p.lastUpdate);
        detail.put("history", historySnapshotForServer(name));
        ctx.json(detail);
    }

    private void apiExtendServer(RequestContext ctx) {
        String name = ctx.pathParam("name");
        if (extenderService == null) { ctx.status(500).json(Map.of("error", "No extender service")); return; }

        String extenderId = findExtenderIdByServer(name);
        if (extenderId == null) {
            ctx.status(404).json(Map.of("error", "No extender found for server " + name));
            return;
        }
        if (bundledExtenderJar == null || bundledExtenderJar.length == 0) {
            ctx.status(500).json(Map.of("error", "Bundled profiler-extender.jar not found"));
            return;
        }

        sendDeployToExtender(extenderId);
        sendConfigToExtender(extenderId);
        ctx.json(Map.of("success", true, "message", "Profiler extension requested", "extender", extenderId));
    }

    private void apiUnextendServer(RequestContext ctx) {
        String name = ctx.pathParam("name");
        if (extenderService == null) { ctx.status(500).json(Map.of("error", "No extender service")); return; }

        String extenderId = findExtenderIdByServer(name);
        if (extenderId == null) {
            ctx.status(404).json(Map.of("error", "No extender found for server " + name));
            return;
        }

        sendUndeployToExtender(extenderId);
        ctx.json(Map.of("success", true, "message", "Profiler extension removed", "extender", extenderId));
    }

    private void saveDeploymentState() {
        if (configManager == null || context == null) return;
        try {
            Map<String, Object> cfg = configManager.getModuleConfig(context.getDescriptor().id());
            List<String> deployed = new ArrayList<>(extendedExtenders);
            deployed.sort(String::compareToIgnoreCase);
            cfg.put("deployed_extenders", deployed);
            configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
        } catch (Exception e) {
            logger.debug("Failed to persist profiler deployment state: {}", e.getMessage());
        }
    }

    private String findExtenderIdByServer(String serverIdentifier) {
        if (extenderService == null || serverIdentifier == null) return null;

        Map<String, Map<String, Object>> states = extenderService.getServerStates();

        // Composite identifier from dashboard target rows: "extenderId|ip:port"
        int pipe = serverIdentifier.indexOf('|');
        if (pipe > 0) {
            String rawExtenderId = serverIdentifier.substring(0, pipe);
            if (states.containsKey(rawExtenderId)) {
                return rawExtenderId;
            }
        }

        // First try: direct extender_id match
        if (states.containsKey(serverIdentifier)) {
            return serverIdentifier;
        }

        // Support passing the server label directly.
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            String stateServer = String.valueOf(entry.getValue().getOrDefault("server", ""));
            if (serverIdentifier.equalsIgnoreCase(stateServer)) {
                return entry.getKey();
            }
        }

        ServerMatchRef ref = parseServerIdentifier(serverIdentifier);
        if (ref != null) {
            // Prefer strong identity (ip+port) if available
            for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
                Map<String, Object> state = entry.getValue();
                String ip = String.valueOf(state.getOrDefault("server_ip", ""));
                int port = intFrom(state.getOrDefault("server_port", 0));
                if (!ref.ip().isBlank() && ref.ip().equalsIgnoreCase(ip) && ref.port() > 0 && ref.port() == port) {
                    return entry.getKey();
                }
            }

            if (!ref.ip().isBlank()) {
                for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
                    String ip = String.valueOf(entry.getValue().getOrDefault("server_ip", ""));
                    if (ref.ip().equalsIgnoreCase(ip)) {
                        return entry.getKey();
                    }
                }
            }
        }

        // Last fallback: plain server IP match
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            Object ip = entry.getValue().get("server_ip");
            if (ip != null && serverIdentifier.equalsIgnoreCase(String.valueOf(ip))) {
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
        c.put("history_max_points", historyMaxPoints);
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
        volatile String extenderId;
        volatile double tps10s, tps1m, tps5m;
        volatile double msptMean, msptMin, msptMax, msptP95;
        volatile double systemCpu, processCpu;
        volatile long heapUsedMb, heapMaxMb;
        volatile long gcCollections, gcTimeMs;
        volatile double gcFrequencyPerMin, gcAvgPauseMs;
        volatile long totalSamples;
        volatile long cumulativeSamples;
        volatile List<Map<String, Object>> topMethods;
        volatile String sampleTreeJson;
        volatile String hotMethodTreeJson;
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

    static class MetricSample {
        final long ts;
        final double tps;
        final double cpu;

        MetricSample(long ts, double tps, double cpu) {
            this.ts = ts;
            this.tps = tps;
            this.cpu = cpu;
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private record ServerMatchRef(String ip, int port) {}

    private String buildIdentifier(String extenderId, String ip, int port) {
        if (ip == null || ip.isBlank() || port <= 0) {
            return extenderId;
        }
        return extenderId + "|" + ip + ":" + port;
    }

    private ServerMatchRef parseServerIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        int pipe = value.indexOf('|');
        String raw = pipe >= 0 ? value.substring(pipe + 1) : value;
        int colon = raw.lastIndexOf(':');
        if (colon <= 0 || colon >= raw.length() - 1) return null;
        String ip = raw.substring(0, colon).trim();
        int port = intFrom(raw.substring(colon + 1));
        if (ip.isBlank()) return null;
        return new ServerMatchRef(ip, port);
    }

    private int intFrom(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String extractServerName(ExtenderMessage msg) {
        JsonObject payload = msg.payload();
        if (payload != null && payload.has("server")) {
            try {
                String explicit = payload.get("server").getAsString();
                if (explicit != null && !explicit.isBlank()) {
                    return explicit;
                }
            } catch (Exception ignored) {
            }
        }
        return msg.serverLabel();
    }

    private ServerProfile findProfileByExtenderId(String extenderId) {
        if (extenderId == null || extenderId.isBlank()) {
            return null;
        }
        for (ServerProfile profile : serverProfiles.values()) {
            if (extenderId.equals(profile.extenderId)) {
                return profile;
            }
        }
        return null;
    }

    private int resolvePlayersForServer(String serverName) {
        if (extenderService == null || serverName == null || serverName.isBlank()) {
            return 0;
        }
        Map<String, Map<String, Object>> states = extenderService.getServerStates();

        // Primary lookup: profile already knows which extender produced this server's profiling data.
        ServerProfile profile = serverProfiles.get(serverName);
        if (profile != null && profile.extenderId != null && !profile.extenderId.isBlank()) {
            Map<String, Object> state = states.get(profile.extenderId);
            if (state != null) {
                return intFrom(state.getOrDefault("players", 0));
            }
        }

        // Fallback: match by server label for older cached profiles.
        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            Map<String, Object> state = entry.getValue();
            String stateName = String.valueOf(state.getOrDefault("server", ""));
            if (serverName.equalsIgnoreCase(stateName)) {
                return intFrom(state.getOrDefault("players", 0));
            }
        }
        return 0;
    }

    private Object parseJsonObject(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return Map.of();
        }
        try {
            return gson.fromJson(rawJson, Object.class);
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private double round(double v) { return Math.round(v * 100.0) / 100.0; }

    private void appendHistorySample(String server, long ts, double tps, double cpu) {
        if (server == null || server.isBlank()) return;
        final long normalizedTs = ts > 0 ? ts : System.currentTimeMillis();
        final double normalizedTps = Double.isFinite(tps) ? Math.max(0, tps) : 0;
        final double normalizedCpu = Double.isFinite(cpu) ? Math.max(0, cpu) : 0;

        Deque<MetricSample> history = historyByServer.computeIfAbsent(server, ignored -> new ArrayDeque<>());
        synchronized (history) {
            history.addLast(new MetricSample(normalizedTs, normalizedTps, normalizedCpu));
            while (history.size() > historyMaxPoints) {
                history.removeFirst();
            }
        }
        historyDirty = true;
    }

    private Map<String, Object> historySnapshotForServer(String server) {
        Deque<MetricSample> history = historyByServer.get(server);
        if (history == null) {
            return Map.of("timestamps", List.of(), "tps", List.of(), "cpu", List.of());
        }

        List<Long> timestamps = new ArrayList<>();
        List<Double> tps = new ArrayList<>();
        List<Double> cpu = new ArrayList<>();

        synchronized (history) {
            for (MetricSample sample : history) {
                timestamps.add(sample.ts);
                tps.add(round(sample.tps));
                cpu.add(round(sample.cpu));
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("timestamps", timestamps);
        out.put("tps", tps);
        out.put("cpu", cpu);
        return out;
    }

    private void trimAllHistoryToRetention() {
        for (Deque<MetricSample> history : historyByServer.values()) {
            synchronized (history) {
                while (history.size() > historyMaxPoints) {
                    history.removeFirst();
                    historyDirty = true;
                }
            }
        }
    }

    private void flushHistoryIfDirty() {
        if (!historyDirty) return;
        flushHistoryDatabase();
    }

    private void loadHistoryDatabase() {
        if (historyDbFile == null) return;
        try {
            Path parent = historyDbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(historyDbFile)) {
                return;
            }

            String raw = Files.readString(historyDbFile);
            if (raw == null || raw.isBlank()) {
                return;
            }
            JsonObject root = gson.fromJson(raw, JsonObject.class);
            if (root == null || !root.has("servers") || !root.get("servers").isJsonObject()) {
                return;
            }

            JsonObject servers = root.getAsJsonObject("servers");
            for (String server : servers.keySet()) {
                JsonElement el = servers.get(server);
                if (!el.isJsonArray()) continue;

                Deque<MetricSample> history = new ArrayDeque<>();
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement sampleEl : arr) {
                    if (!sampleEl.isJsonObject()) continue;
                    JsonObject s = sampleEl.getAsJsonObject();
                    long ts = s.has("ts") ? s.get("ts").getAsLong() : 0L;
                    double tps = s.has("tps") ? s.get("tps").getAsDouble() : 0D;
                    double cpu = s.has("cpu") ? s.get("cpu").getAsDouble() : 0D;
                    history.addLast(new MetricSample(ts, tps, cpu));
                    while (history.size() > historyMaxPoints) {
                        history.removeFirst();
                    }
                }
                if (!history.isEmpty()) {
                    historyByServer.put(server, history);
                }
            }
            historyDirty = false;
        } catch (Exception e) {
            logger.warn("Failed to load profiler history database: {}", e.getMessage());
        }
    }

    private void flushHistoryDatabase() {
        if (historyDbFile == null) return;
        try {
            Path parent = historyDbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            JsonObject root = new JsonObject();
            root.addProperty("generated_at", Instant.now().toEpochMilli());
            root.addProperty("history_max_points", historyMaxPoints);

            JsonObject servers = new JsonObject();
            List<String> names = new ArrayList<>(historyByServer.keySet());
            names.sort(String::compareToIgnoreCase);
            for (String server : names) {
                Deque<MetricSample> history = historyByServer.get(server);
                if (history == null) continue;

                JsonArray points = new JsonArray();
                synchronized (history) {
                    for (MetricSample sample : history) {
                        JsonObject row = new JsonObject();
                        row.addProperty("ts", sample.ts);
                        row.addProperty("tps", round(sample.tps));
                        row.addProperty("cpu", round(sample.cpu));
                        points.add(row);
                    }
                }
                servers.add(server, points);
            }
            root.add("servers", servers);

            Path temp = historyDbFile.resolveSibling(historyDbFile.getFileName() + ".tmp");
            Files.writeString(temp, gson.toJson(root), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            try {
                Files.move(temp, historyDbFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                Files.move(temp, historyDbFile, StandardCopyOption.REPLACE_EXISTING);
            }
            historyDirty = false;
        } catch (Exception e) {
            logger.warn("Failed to flush profiler history database: {}", e.getMessage());
        }
    }

    private boolean boolCfg(Map<String, Object> m, String k, boolean d) {
        Object v = m.get(k); return v instanceof Boolean b ? b : d;
    }
    private int intCfg(Map<String, Object> m, String k, int d) {
        Object v = m.get(k); return v instanceof Number n ? n.intValue() : d;
    }
}

