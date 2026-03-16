package dev.erikradovan.spikedetector;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
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
import com.velocitypowered.api.proxy.ProxyServer;

import java.net.InetAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects backend chunk lag spikes from extender profiling reports.
 */
public class SpikeDetectorModule implements dev.erikradovan.integritypolygon.api.Module {

    private static final int MAX_INCIDENT_HISTORY = 250;

    private final Gson gson = new Gson();

    private ModuleContext context;
    private ExtenderService extenderService;
    private ConfigManager configManager;
    private AlertService alertService;
    private LogManager logManager;
    private ProxyServer proxyServer;

    private volatile boolean enabled = true;
    private volatile int profilingIntervalSec = 12;
    private volatile int topChunks = 40;
    private volatile double warnTickMs = 75.0;
    private volatile double criticalTickMs = 140.0;
    private volatile int defaultEntityWarn = 250;
    private volatile int defaultTileWarn = 100;
    private volatile boolean autoMitigateCritical = true;

    private final ConcurrentHashMap<String, ServerState> serverStates = new ConcurrentHashMap<>();
    private final Deque<SpikeIncident> incidentHistory = new LinkedList<>();
    private final AtomicLong totalWarnSpikes = new AtomicLong();
    private final AtomicLong totalCriticalSpikes = new AtomicLong();

    @Override
    public void onEnable(ModuleContext ctx) {
        this.context = ctx;

        ServiceRegistry reg = ctx.getServiceRegistry();
        this.extenderService = reg.get(ExtenderService.class).orElse(null);
        this.configManager = reg.get(ConfigManager.class).orElse(null);
        this.alertService = reg.get(AlertService.class).orElse(null);
        this.logManager = reg.get(LogManager.class).orElse(null);
        this.proxyServer = reg.get(ProxyServer.class).orElse(null);

        loadConfig();

        if (extenderService != null) {
            extenderService.subscribeToEvent("spike-detector", "profiling_report", this::handleProfilingReport);
            extenderService.onMessage("spike-detector", "event:mitigation_result", this::handleMitigationResult);
            extenderService.onMessage("spike-detector", "heartbeat", this::handleHeartbeat);
        }

        context.getTaskScheduler().scheduleAtFixedRate(this::requestProfiles, 5, profilingIntervalSec, TimeUnit.SECONDS);
        registerDashboard();
        log("INFO", "LIFECYCLE", "Spike Detector enabled");
    }

    @Override
    public void onDisable() {
        if (extenderService != null) {
            extenderService.removeAllHandlers("spike-detector");
        }
        log("INFO", "LIFECYCLE", "Spike Detector disabled");
    }

    @Override
    public void onReload() {
        loadConfig();
        log("INFO", "CONFIG", "Spike Detector configuration reloaded");
    }

    private void loadConfig() {
        if (configManager == null) {
            return;
        }

        Map<String, Object> cfg = configManager.getModuleConfig(context.getDescriptor().id());
        if (cfg.isEmpty()) {
            saveDefaults();
            cfg = configManager.getModuleConfig(context.getDescriptor().id());
        }

        enabled = boolCfg(cfg, "enabled", true);
        profilingIntervalSec = intCfg(cfg, "profiling_interval_sec", 12);
        topChunks = intCfg(cfg, "top_chunks", 40);
        warnTickMs = dblCfg(cfg, "warn_tick_ms", 75.0);
        criticalTickMs = dblCfg(cfg, "critical_tick_ms", 140.0);
        defaultEntityWarn = intCfg(cfg, "default_entity_warn", 250);
        defaultTileWarn = intCfg(cfg, "default_tile_warn", 100);
        autoMitigateCritical = boolCfg(cfg, "auto_mitigate_critical", true);
    }

    private void saveDefaults() {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", true);
        cfg.put("profiling_interval_sec", 12);
        cfg.put("top_chunks", 40);
        cfg.put("warn_tick_ms", 75.0);
        cfg.put("critical_tick_ms", 140.0);
        cfg.put("default_entity_warn", 250);
        cfg.put("default_tile_warn", 100);
        cfg.put("auto_mitigate_critical", true);
        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
    }

    private void requestProfiles() {
        if (!enabled || extenderService == null) {
            return;
        }

        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("action", "profile");
        cmd.put("top_chunks", topChunks);
        extenderService.broadcastCommand("spike-detector", cmd);
    }

    private void handleHeartbeat(ExtenderMessage msg) {
        String server = msg.source();
        ServerState state = serverStates.computeIfAbsent(server, ServerState::new);
        state.serverLabel = msg.serverLabel();
        state.lastHeartbeat = System.currentTimeMillis();
    }

    private void handleProfilingReport(ExtenderMessage msg) {
        String server = msg.source();
        String reportRaw = msg.getString("report");
        if (reportRaw == null || reportRaw.isBlank()) {
            return;
        }

        ServerState state = serverStates.computeIfAbsent(server, ServerState::new);
        state.serverLabel = msg.serverLabel();
        state.lastReport = System.currentTimeMillis();

        JsonObject report = JsonParser.parseString(reportRaw).getAsJsonObject();
        JsonArray chunks = report.has("chunks") ? report.getAsJsonArray("chunks") : new JsonArray();

        List<ChunkSnapshot> flagged = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            JsonObject c = chunks.get(i).getAsJsonObject();
            String world = c.has("world") ? c.get("world").getAsString() : "world";
            int x = c.has("x") ? c.get("x").getAsInt() : 0;
            int z = c.has("z") ? c.get("z").getAsInt() : 0;
            double tickMs = c.has("tick_ms") ? c.get("tick_ms").getAsDouble() : 0.0;
            int entities = c.has("entity_count") ? c.get("entity_count").getAsInt() : 0;
            int tiles = c.has("tile_entity_count") ? c.get("tile_entity_count").getAsInt() : 0;
            boolean critical = tickMs >= criticalTickMs || entities >= defaultEntityWarn * 2 || tiles >= defaultTileWarn * 2;
            boolean warn = !critical && (tickMs >= warnTickMs || entities >= defaultEntityWarn || tiles >= defaultTileWarn);
            if (!warn && !critical) {
                continue;
            }

            ChunkSnapshot snapshot = new ChunkSnapshot(world, x, z, tickMs, entities, tiles, critical);
            flagged.add(snapshot);
            SpikeIncident incident = new SpikeIncident(server, snapshot, Instant.now(), critical ? "critical" : "warn");
            addIncident(incident);

            if (critical) {
                totalCriticalSpikes.incrementAndGet();
                if (autoMitigateCritical) {
                    requestMitigation(server, snapshot);
                }
            } else {
                totalWarnSpikes.incrementAndGet();
            }
        }

        flagged.sort(Comparator.comparingDouble(ChunkSnapshot::tickMs).reversed());
        state.flagged = flagged;
    }

    private void requestMitigation(String server, ChunkSnapshot snapshot) {
        if (extenderService == null) {
            return;
        }

        String extenderId = resolveExtenderIdByServerLabel(server);
        if (extenderId == null) {
            return;
        }

        JsonObject payload = new JsonObject();
        payload.addProperty("action", "spike_mitigate");
        payload.addProperty("world", snapshot.world());
        payload.addProperty("x", snapshot.x());
        payload.addProperty("z", snapshot.z());
        payload.addProperty("mode", "clear_entities");
        extenderService.sendMessage("spike-detector", extenderId, "command", payload);
    }

    private String resolveExtenderIdByServerLabel(String serverLabel) {
        if (extenderService == null || serverLabel == null || serverLabel.isBlank()) {
            return null;
        }

        // Prefer direct extender id match.
        if (extenderService.getServerStates().containsKey(serverLabel)) {
            return serverLabel;
        }

        for (Map.Entry<String, Map<String, Object>> entry : extenderService.getServerStates().entrySet()) {
            Object label = entry.getValue().get("server");
            if (serverLabel.equals(String.valueOf(label))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void handleMitigationResult(ExtenderMessage msg) {
        String server = msg.serverLabel();
        String world = msg.getString("world");
        if (world == null || world.isBlank()) {
            world = "world";
        }
        int x = msg.getInt("x", 0);
        int z = msg.getInt("z", 0);
        int removed = msg.getInt("removed_entities", 0);
        String text = "Mitigated chunk " + world + " [" + x + ", " + z + "] on " + server + " (removed entities=" + removed + ")";
        log("WARN", "MITIGATION", text);
        if (alertService != null) {
            alertService.sendAlert(AlertService.Severity.WARNING, "Spike mitigation", text);
        }
    }

    private synchronized void addIncident(SpikeIncident incident) {
        incidentHistory.addLast(incident);
        while (incidentHistory.size() > MAX_INCIDENT_HISTORY) {
            incidentHistory.removeFirst();
        }
    }

    private void registerDashboard() {
        ModuleDashboard d = context.getDashboard();
        d.get("status", this::apiStatus);
        d.get("servers", this::apiServers);
        d.get("server/{name}", this::apiServerDetail);
        d.get("incidents", this::apiIncidents);
        d.get("config", this::apiConfig);
        d.post("config", this::apiSaveConfig);
        d.post("mitigate/{server}/{world}/{x}/{z}", this::apiMitigate);
    }

    private void apiStatus(RequestContext ctx) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("servers", serverStates.size());
        status.put("warn_spikes", totalWarnSpikes.get());
        status.put("critical_spikes", totalCriticalSpikes.get());
        status.put("auto_mitigate_critical", autoMitigateCritical);
        status.put("profiling_interval_sec", profilingIntervalSec);
        status.put("warn_tick_ms", warnTickMs);
        status.put("critical_tick_ms", criticalTickMs);
        ctx.json(status);
    }

    private void apiServers(RequestContext ctx) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServerState st : serverStates.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", st.id);
            row.put("name", resolveDisplayName(st));
            row.put("flagged", st.flagged.size());
            row.put("last_report", st.lastReport);
            row.put("last_heartbeat", st.lastHeartbeat);
            row.put("top_tick_ms", st.flagged.isEmpty() ? 0.0 : st.flagged.get(0).tickMs());
            row.put("critical_count", st.flagged.stream().filter(ChunkSnapshot::critical).count());
            rows.add(row);
        }
        rows.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        ctx.json(Map.of("servers", rows));
    }

    private void apiServerDetail(RequestContext ctx) {
        String name = ctx.pathParam("name");
        ServerState st = serverStates.get(name);
        if (st == null) {
            ctx.status(404).json(Map.of("error", "Server not found"));
            return;
        }
        ctx.json(Map.of(
                "id", st.id,
                "name", resolveDisplayName(st),
                "last_report", st.lastReport,
                "flagged", st.flagged
        ));
    }

    private void apiIncidents(RequestContext ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (this) {
            for (SpikeIncident incident : incidentHistory) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("server", incident.server());
                row.put("severity", incident.severity());
                row.put("timestamp", incident.timestamp().toString());
                row.put("chunk", incident.snapshot());
                out.add(row);
            }
        }
        ctx.json(Map.of("incidents", out));
    }

    private void apiConfig(RequestContext ctx) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("enabled", enabled);
        cfg.put("profiling_interval_sec", profilingIntervalSec);
        cfg.put("top_chunks", topChunks);
        cfg.put("warn_tick_ms", warnTickMs);
        cfg.put("critical_tick_ms", criticalTickMs);
        cfg.put("default_entity_warn", defaultEntityWarn);
        cfg.put("default_tile_warn", defaultTileWarn);
        cfg.put("auto_mitigate_critical", autoMitigateCritical);
        ctx.json(cfg);
    }

    private void apiSaveConfig(RequestContext ctx) {
        if (configManager == null) {
            ctx.status(500).json(Map.of("error", "Config unavailable"));
            return;
        }

        JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
        Map<String, Object> cfg = configManager.getModuleConfig(context.getDescriptor().id());
        body.entrySet().forEach(e -> {
            if (e.getValue().isJsonPrimitive()) {
                var p = e.getValue().getAsJsonPrimitive();
                if (p.isBoolean()) {
                    cfg.put(e.getKey(), p.getAsBoolean());
                } else if (p.isNumber()) {
                    cfg.put(e.getKey(), p.getAsNumber());
                } else {
                    cfg.put(e.getKey(), p.getAsString());
                }
            }
        });

        configManager.saveModuleConfig(context.getDescriptor().id(), cfg);
        loadConfig();
        ctx.json(Map.of("success", true));
    }

    private void apiMitigate(RequestContext ctx) {
        String server = ctx.pathParam("server");
        String world = ctx.pathParam("world");
        int x = Integer.parseInt(ctx.pathParam("x"));
        int z = Integer.parseInt(ctx.pathParam("z"));

        ChunkSnapshot snapshot = new ChunkSnapshot(world, x, z, 0.0, 0, 0, true);
        requestMitigation(server, snapshot);
        ctx.json(Map.of("success", true));
    }

    private void log(String level, String tag, String message) {
        if (logManager != null) {
            logManager.log("spike-detector", level, tag, message);
        }
    }

    private boolean boolCfg(Map<String, Object> map, String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean b ? b : def;
    }

    private int intCfg(Map<String, Object> map, String key, int def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    private double dblCfg(Map<String, Object> map, String key, double def) {
        Object v = map.get(key);
        return v instanceof Number n ? n.doubleValue() : def;
    }

    static class ServerState {
        final String id;
        volatile String serverLabel;
        volatile long lastHeartbeat;
        volatile long lastReport;
        volatile List<ChunkSnapshot> flagged = List.of();

        ServerState(String id) {
            this.id = id;
        }
    }

    private String resolveDisplayName(ServerState state) {
        if (state == null) {
            return "unknown";
        }
        if (extenderService != null) {
            Map<String, Object> ext = extenderService.getServerStates().get(state.id);
            if (ext != null) {
                String resolved = resolveProxyServerName(ext);
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        if (state.serverLabel != null && !state.serverLabel.isBlank()) {
            return state.serverLabel;
        }
        return state.id.length() > 12 ? state.id.substring(0, 12) : state.id;
    }

    private String resolveProxyServerName(Map<String, Object> extState) {
        if (proxyServer == null || extState == null) {
            return null;
        }
        String extIp = String.valueOf(extState.getOrDefault("server_ip", "")).trim();
        int extPort = extState.get("server_port") instanceof Number n ? n.intValue() : 0;
        if (extIp.isBlank() || extPort <= 0) {
            return null;
        }
        Set<String> extVariants = buildHostVariants(extIp);
        for (var registered : proxyServer.getAllServers()) {
            String host = registered.getServerInfo().getAddress().getHostString();
            int port = registered.getServerInfo().getAddress().getPort();
            if (port != extPort) {
                continue;
            }
            if (!Collections.disjoint(extVariants, buildHostVariants(host))) {
                return registered.getServerInfo().getName();
            }
        }
        return null;
    }

    private Set<String> buildHostVariants(String host) {
        Set<String> variants = new HashSet<>();
        if (host == null || host.isBlank()) {
            return variants;
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        variants.add(normalized);
        try {
            InetAddress addr = InetAddress.getByName(normalized);
            variants.add(addr.getHostAddress().toLowerCase(Locale.ROOT));
            variants.add(addr.getHostName().toLowerCase(Locale.ROOT));
            variants.add(addr.getCanonicalHostName().toLowerCase(Locale.ROOT));
        } catch (Exception ignored) {
        }
        return variants;
    }

    record ChunkSnapshot(String world, int x, int z, double tickMs, int entityCount, int tileEntityCount,
                         boolean critical) {
    }

    record SpikeIncident(String server, ChunkSnapshot snapshot, Instant timestamp, String severity) {
    }
}


