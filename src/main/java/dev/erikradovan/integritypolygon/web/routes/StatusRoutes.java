package dev.erikradovan.integritypolygon.web.routes;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.erikradovan.integritypolygon.core.ExtenderServiceImpl;
import io.javalin.http.Context;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.*;

/**
 * REST API routes for server status and monitoring.
 */
public class StatusRoutes {

    private static final long HEARTBEAT_STALE_MS = 90_000L;

    private final ProxyServer proxy;
    private final long startTime;
    private ExtenderServiceImpl extenderService;

    public StatusRoutes(ProxyServer proxy) {
        this.proxy = proxy;
        this.startTime = System.currentTimeMillis();
    }

    public void setExtenderService(ExtenderServiceImpl extenderService) {
        this.extenderService = extenderService;
    }

    /**
     * GET /api/status — returns current server status.
     */
    public void getStatus(Context ctx) {
        try {
            Map<String, Object> status = new LinkedHashMap<>();

            // Uptime
            long uptimeMs = System.currentTimeMillis() - startTime;
            status.put("uptime_ms", uptimeMs);
            status.put("uptime_formatted", formatUptime(uptimeMs));

            // Player count
            int totalPlayers = proxy.getPlayerCount();
            status.put("total_players", totalPlayers);

            // Per-server info (from Velocity registry)
            List<Map<String, Object>> servers = new ArrayList<>();
            Map<String, Map<String, Object>> extenderStates = Map.of();
            try {
                if (extenderService != null) {
                    extenderStates = extenderService.getServerStates();
                }
            } catch (Exception ignored) {}

            for (RegisteredServer server : proxy.getAllServers()) {
                try {
                    Map<String, Object> serverInfo = new LinkedHashMap<>();
                    String name = server.getServerInfo().getName();
                    serverInfo.put("name", name);
                    serverInfo.put("address", server.getServerInfo().getAddress().toString());
                    serverInfo.put("players", server.getPlayersConnected().size());

                    // Merge extender data if available
                    // State map is keyed by extender hash; look for one whose "server" label matches
                    Map<String, Object> ext = null;
                    String serverHost = normalizeHost(server.getServerInfo().getAddress().getHostString());
                    for (Map.Entry<String, Map<String, Object>> entry : extenderStates.entrySet()) {
                        String label = String.valueOf(entry.getValue().getOrDefault("server", ""));
                        if (name.equalsIgnoreCase(label)) {
                            ext = entry.getValue();
                            break;
                        }
                    }

                    // Fallback: match by backend IP when labels differ.
                    if (ext == null && serverHost != null && !serverHost.isBlank()) {
                        for (Map.Entry<String, Map<String, Object>> entry : extenderStates.entrySet()) {
                            String extIp = normalizeHost(String.valueOf(entry.getValue().getOrDefault("server_ip", "")));
                            if (extIp != null && !extIp.isBlank() && serverHost.equalsIgnoreCase(extIp)) {
                                ext = entry.getValue();
                                break;
                            }
                        }
                    }

                    if (ext != null) {
                        long lastHeartbeat = toLong(ext.getOrDefault("last_heartbeat", 0L));
                        boolean fresh = lastHeartbeat > 0L && (System.currentTimeMillis() - lastHeartbeat) <= HEARTBEAT_STALE_MS;
                        if (!fresh) {
                            ext = null;
                        }
                    }

                    if (ext != null) {
                        serverInfo.put("extender_enrolled", true);
                        serverInfo.put("tps", ext.getOrDefault("tps", 0.0));
                        serverInfo.put("memory_used_mb", ext.getOrDefault("memory_used_mb", 0));
                        serverInfo.put("memory_max_mb", ext.getOrDefault("memory_max_mb", 0));
                        serverInfo.put("mc_version", ext.getOrDefault("mc_version", "?"));
                        serverInfo.put("max_players", ext.getOrDefault("max_players", 0));
                        serverInfo.put("cpu_usage", ext.getOrDefault("cpu_usage", -1.0));
                        serverInfo.put("last_heartbeat", toLong(ext.getOrDefault("last_heartbeat", 0L)));
                    } else {
                        serverInfo.put("extender_enrolled", false);
                    }

                    servers.add(serverInfo);
                } catch (Exception ignored) {
                    // Skip this server if there's an issue extracting its info
                }
            }
            status.put("servers", servers);

            // Memory
            MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
            Map<String, Object> memory = new LinkedHashMap<>();
            memory.put("heap_used_mb", memoryBean.getHeapMemoryUsage().getUsed() / (1024 * 1024));
            memory.put("heap_max_mb", memoryBean.getHeapMemoryUsage().getMax() / (1024 * 1024));
            memory.put("non_heap_used_mb", memoryBean.getNonHeapMemoryUsage().getUsed() / (1024 * 1024));
            status.put("memory", memory);

            // JVM info
            status.put("java_version", System.getProperty("java.version"));
            status.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));

            ctx.json(status);
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Failed to get status: " + e.getMessage()));
        }
    }

    /**
     * GET /api/extenders — returns detailed state of all backend servers with Extender installed.
     */
    public void getExtenders(Context ctx) {
        if (extenderService == null) {
            ctx.json(Map.of("servers", List.of()));
            return;
        }
        ctx.json(Map.of("servers", new ArrayList<>(extenderService.getServerStates().values())));
    }

    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        return String.format("%dd %dh %dm %ds", days, hours % 24, minutes % 60, seconds % 60);
    }

    private long toLong(Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String normalizeHost(String value) {
        if (value == null) return "";
        String host = value.trim();
        if (host.startsWith("/")) host = host.substring(1);
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host;
    }
}

