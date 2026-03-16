package dev.erikradovan.integritypolygon.web.websocket;

import com.google.gson.Gson;
import dev.erikradovan.integritypolygon.logging.LogEvent;
import dev.erikradovan.integritypolygon.logging.LogManager;
import dev.erikradovan.integritypolygon.web.auth.AuthManager;
import io.javalin.websocket.WsConfig;
import io.javalin.websocket.WsContext;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * WebSocket handler for real-time log streaming and status updates.
 * Clients connect at {@code /ws/live?token=<JWT>} and receive push notifications
 * for new log events and module state changes.
 */
public class RealtimeHandler implements Consumer<WsConfig> {

    private final AuthManager authManager;
    private final LogManager logManager;
    private final Logger logger;
    private final Gson gson = new Gson();
    private final Set<WsContext> sessions = ConcurrentHashMap.newKeySet();
    private final Consumer<LogEvent> logListener;
    private ScheduledExecutorService statusTicker;
    private Supplier<Integer> playerCountSupplier;

    public RealtimeHandler(AuthManager authManager, LogManager logManager, Logger logger) {
        this.authManager = authManager;
        this.logManager = logManager;
        this.logger = logger;
        this.logListener = this::broadcastLogEvent;
        logManager.addListener(logListener);
    }

    @Override
    public void accept(WsConfig ws) {
        ws.onConnect(ctx -> {
            String token = ctx.queryParam("token");
            if (token == null || authManager.validateToken(token).isEmpty()) {
                ctx.closeSession(4001, "Unauthorized");
                return;
            }
            sessions.add(ctx);
            logger.debug("WebSocket client connected (total: {})", sessions.size());
            ctx.send(gson.toJson(Map.of("type", "connected", "message", "Connected to IntegrityPolygon")));
        });

        ws.onClose(ctx -> {
            sessions.remove(ctx);
            logger.debug("WebSocket client disconnected (total: {})", sessions.size());
        });

        ws.onError(ctx -> {
            sessions.remove(ctx);
            if (ctx.error() != null) {
                logger.debug("WebSocket error: {}", ctx.error().getMessage());
            }
        });
    }

    private void broadcastLogEvent(LogEvent event) {
        if (sessions.isEmpty()) return;

        String json = gson.toJson(Map.of(
                "type", "log",
                "moduleId", event.moduleId(),
                "level", event.level(),
                "tag", event.tag(),
                "message", event.message(),
                "timestamp", event.timestamp().toString()
        ));

        for (WsContext session : sessions) {
            try {
                session.send(json);
            } catch (Exception e) {
                sessions.remove(session);
            }
        }
    }

    /**
     * Broadcast a custom event to all connected WebSocket clients.
     *
     * @param type the event type identifier
     * @param data additional key-value data to include
     */
    public void broadcast(String type, Map<String, Object> data) {
        if (sessions.isEmpty()) return;

        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.putAll(data);

        String json = gson.toJson(message);
        for (WsContext session : sessions) {
            try {
                session.send(json);
            } catch (Exception e) {
                sessions.remove(session);
            }
        }
    }

    /**
     * Start periodic status broadcasts (every 3 seconds) to all connected clients.
     * Sends player count, memory usage, and uptime.
     */
    public void startStatusTicker(Supplier<Integer> playerCountSupplier, long startTimeMs) {
        this.playerCountSupplier = playerCountSupplier;
        this.statusTicker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "IP-StatusTicker");
            t.setDaemon(true);
            return t;
        });
        statusTicker.scheduleAtFixedRate(() -> {
            if (sessions.isEmpty()) return;
            try {
                MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
                long uptimeMs = System.currentTimeMillis() - startTimeMs;
                Map<String, Object> status = new LinkedHashMap<>();
                status.put("type", "status");
                status.put("players", playerCountSupplier.get());
                status.put("heap_used_mb", mem.getHeapMemoryUsage().getUsed() / (1024 * 1024));
                status.put("heap_max_mb", mem.getHeapMemoryUsage().getMax() / (1024 * 1024));
                status.put("uptime_ms", uptimeMs);
                String json = gson.toJson(status);
                for (WsContext session : sessions) {
                    try { session.send(json); } catch (Exception e) { sessions.remove(session); }
                }
            } catch (Exception ignored) {}
        }, 3, 3, TimeUnit.SECONDS);
    }

    /**
     * Close all sessions and unregister the log listener.
     */
    public void shutdown() {
        logManager.removeListener(logListener);
        if (statusTicker != null) {
            statusTicker.shutdownNow();
        }
        for (WsContext session : sessions) {
            try {
                session.closeSession();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
    }
}
