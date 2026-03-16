package dev.erikradovan.integritypolygon.logging;

import java.time.Instant;

/**
 * Immutable log event record.
 *
 * @param moduleId  the source module ID (or "system" for framework events)
 * @param level     log level: INFO, WARN, ERROR, DEBUG
 * @param tag       categorization tag
 * @param message   the log message
 * @param timestamp when the event occurred
 */
public record LogEvent(
        String moduleId,
        String level,
        String tag,
        String message,
        Instant timestamp
) {
    public LogEvent(String moduleId, String level, String tag, String message) {
        this(moduleId, level, tag, message, Instant.now());
    }
}

