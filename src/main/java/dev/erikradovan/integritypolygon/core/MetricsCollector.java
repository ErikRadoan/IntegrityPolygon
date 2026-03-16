package dev.erikradovan.integritypolygon.core;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.api.MetricsService;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Collects and publishes core proxy and backend server metrics to both:
 * <ul>
 *   <li>{@link MetricsService} — Prometheus exposition (for external scraping)</li>
 *   <li>{@link MetricsBuffer} — in-memory ring buffer (for built-in monitoring dashboard)</li>
 * </ul>
 * Runs on a 10-second interval.
 */
public class MetricsCollector {

    private static final String[] EMPTY_LABELS = {};
    private static final String[] SERVER_LABEL = {"server"};

    private final MetricsService metrics;
    private final MetricsBuffer buffer;
    private final ProxyServer proxy;
    private final ExtenderServiceImpl extenderService;
    private final long startTime;
    private final ScheduledExecutorService scheduler;
    private final Logger logger;

    public MetricsCollector(MetricsService metrics, MetricsBuffer buffer,
                            ProxyServer proxy, ExtenderServiceImpl extenderService,
                            Logger logger) {
        this.metrics = metrics;
        this.buffer = buffer;
        this.proxy = proxy;
        this.extenderService = extenderService;
        this.startTime = System.currentTimeMillis();
        this.logger = logger;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IP-MetricsCollector");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::collect, 5, 10, TimeUnit.SECONDS);
        logger.debug("Metrics collector started (10s interval)");
    }

    public void stop() {
        scheduler.shutdownNow();
    }

    private void collect() {
        try {
            collectProxyMetrics();
            collectBackendMetrics();
        } catch (Exception e) {
            logger.debug("Metrics collection error: {}", e.getMessage());
        }
    }

    private void collectProxyMetrics() {
        // Player count
        int players = proxy.getPlayerCount();
        metrics.gaugeSet("ip_proxy_players_total", "Total players on proxy",
                EMPTY_LABELS, EMPTY_LABELS, players);
        buffer.record("proxy.players", players);

        // Uptime
        double uptimeSec = (System.currentTimeMillis() - startTime) / 1000.0;
        metrics.gaugeSet("ip_proxy_uptime_seconds", "Proxy uptime in seconds",
                EMPTY_LABELS, EMPTY_LABELS, uptimeSec);

        // Memory
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long usedBytes = mem.getHeapMemoryUsage().getUsed();
        long maxBytes = mem.getHeapMemoryUsage().getMax();
        double usedMb = usedBytes / (1024.0 * 1024.0);
        double maxMb = maxBytes / (1024.0 * 1024.0);
        metrics.gaugeSet("ip_proxy_memory_used_bytes", "Proxy heap memory used (bytes)",
                EMPTY_LABELS, EMPTY_LABELS, usedBytes);
        metrics.gaugeSet("ip_proxy_memory_max_bytes", "Proxy heap memory max (bytes)",
                EMPTY_LABELS, EMPTY_LABELS, maxBytes);
        buffer.record("proxy.memory_used_mb", usedMb);
        buffer.record("proxy.memory_max_mb", maxMb);

        // CPU
        try {
            var osBean = ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                double cpuLoad = sunBean.getProcessCpuLoad() * 100.0;
                metrics.gaugeSet("ip_proxy_cpu_usage_percent", "Proxy CPU usage percentage",
                        EMPTY_LABELS, EMPTY_LABELS, cpuLoad);
                buffer.record("proxy.cpu", cpuLoad);
            }
        } catch (Exception ignored) {
        }

        // Server count
        metrics.gaugeSet("ip_proxy_server_count", "Number of registered backend servers",
                EMPTY_LABELS, EMPTY_LABELS, proxy.getAllServers().size());
    }

    private void collectBackendMetrics() {
        if (extenderService == null) return;
        Map<String, Map<String, Object>> states = extenderService.getServerStates();

        for (Map.Entry<String, Map<String, Object>> entry : states.entrySet()) {
            String server = entry.getKey();
            Map<String, Object> state = entry.getValue();
            String[] labels = {server};

            Object tps = state.get("tps");
            if (tps instanceof Number n) {
                double v = n.doubleValue();
                metrics.gaugeSet("ip_server_tps", "Backend server TPS",
                        SERVER_LABEL, labels, v);
                buffer.record("server." + server + ".tps", v);
            }

            Object players = state.get("players");
            if (players instanceof Number n) {
                double v = n.doubleValue();
                metrics.gaugeSet("ip_server_players", "Backend server player count",
                        SERVER_LABEL, labels, v);
                buffer.record("server." + server + ".players", v);
            }

            Object memUsed = state.get("memory_used_mb");
            if (memUsed instanceof Number n) {
                double v = n.doubleValue();
                metrics.gaugeSet("ip_server_memory_used_mb", "Backend server memory used (MB)",
                        SERVER_LABEL, labels, v);
                buffer.record("server." + server + ".memory_used_mb", v);
            }

            Object memMax = state.get("memory_max_mb");
            if (memMax instanceof Number n) {
                double v = n.doubleValue();
                metrics.gaugeSet("ip_server_memory_max_mb", "Backend server memory max (MB)",
                        SERVER_LABEL, labels, v);
                buffer.record("server." + server + ".memory_max_mb", v);
            }

            Object cpu = state.get("cpu_usage");
            if (cpu instanceof Number n) {
                double v = n.doubleValue();
                if (v >= 0) {
                    metrics.gaugeSet("ip_server_cpu_usage_percent", "Backend server CPU usage",
                            SERVER_LABEL, labels, v);
                    buffer.record("server." + server + ".cpu", v);
                }
            }
        }
    }
}

