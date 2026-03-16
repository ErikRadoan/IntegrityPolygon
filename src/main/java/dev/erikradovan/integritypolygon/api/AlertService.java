package dev.erikradovan.integritypolygon.api;

import java.util.Map;

/**
 * Service for sending real-time alerts and notifications to the web panel.
 * Alerts are broadcast to all connected WebSocket clients immediately.
 *
 * <p>Modules access this via the service registry:
 * <pre>{@code
 * AlertService alerts = context.getServiceRegistry()
 *     .get(AlertService.class).orElseThrow();
 *
 * alerts.sendAlert(Severity.HIGH, "Bot Attack Detected",
 *     "50 connections/sec from 192.168.1.0/24",
 *     Map.of("module", "antibot", "connections", 50));
 * }</pre>
 */
public interface AlertService {

    /**
     * Send a real-time alert to the web panel.
     *
     * @param severity the alert severity level
     * @param title    short summary of the alert
     * @param message  detailed description
     */
    void sendAlert(Severity severity, String title, String message);

    /**
     * Send a real-time alert with additional metadata.
     *
     * @param severity the alert severity level
     * @param title    short summary of the alert
     * @param message  detailed description
     * @param metadata additional key-value data (sent to the panel as JSON)
     */
    void sendAlert(Severity severity, String title, String message, Map<String, Object> metadata);

    /**
     * Alert severity levels.
     */
    enum Severity {
        /** Informational — routine status updates */
        INFO,
        /** Warning — something noteworthy that may require attention */
        WARNING,
        /** High — active threat or significant issue */
        HIGH,
        /** Critical — immediate action required */
        CRITICAL
    }
}

