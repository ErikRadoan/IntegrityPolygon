package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.AlertService;
import dev.erikradovan.integritypolygon.web.websocket.RealtimeHandler;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends real-time alerts to the web panel via WebSocket.
 */
public class AlertServiceImpl implements AlertService {

    private final RealtimeHandler realtimeHandler;

    public AlertServiceImpl(RealtimeHandler realtimeHandler) {
        this.realtimeHandler = realtimeHandler;
    }

    @Override
    public void sendAlert(Severity severity, String title, String message) {
        sendAlert(severity, title, message, Map.of());
    }

    @Override
    public void sendAlert(Severity severity, String title, String message, Map<String, Object> metadata) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("severity", severity.name());
        data.put("title", title);
        data.put("message", message);
        data.put("timestamp", Instant.now().toString());
        data.putAll(metadata);

        realtimeHandler.broadcast("alert", data);
    }
}

