package dev.erikradovan.integritypolygon.logging;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Centralized log manager. Stores log events in a bounded in-memory buffer
 * and notifies registered listeners (e.g., WebSocket handler for real-time streaming).
 */
public class LogManager {

    private final LogBuffer buffer;
    private final List<Consumer<LogEvent>> listeners = new CopyOnWriteArrayList<>();

    public LogManager() {
        this.buffer = new LogBuffer();
    }

    public LogManager(int bufferSize) {
        this.buffer = new LogBuffer(bufferSize);
    }

    /**
     * Log an event. Stores in the buffer and notifies all listeners.
     */
    public void log(String moduleId, String level, String tag, String message) {
        LogEvent event = new LogEvent(moduleId, level, tag, message);
        buffer.add(event);
        notifyListeners(event);
    }

    public void log(LogEvent event) {
        buffer.add(event);
        notifyListeners(event);
    }

    public void info(String moduleId, String tag, String message) {
        log(moduleId, "INFO", tag, message);
    }

    public void warn(String moduleId, String tag, String message) {
        log(moduleId, "WARN", tag, message);
    }

    public void error(String moduleId, String tag, String message) {
        log(moduleId, "ERROR", tag, message);
    }

    public void addListener(Consumer<LogEvent> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<LogEvent> listener) {
        listeners.remove(listener);
    }

    public LogBuffer getBuffer() {
        return buffer;
    }

    private void notifyListeners(LogEvent event) {
        for (Consumer<LogEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // Never let a listener break the logging pipeline
            }
        }
    }
}
