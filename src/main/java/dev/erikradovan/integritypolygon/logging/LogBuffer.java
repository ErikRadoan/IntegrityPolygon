package dev.erikradovan.integritypolygon.logging;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Thread-safe bounded circular buffer for log events.
 * When the buffer exceeds the max size, oldest entries are removed.
 */
public class LogBuffer {

    private final Deque<LogEvent> buffer = new ConcurrentLinkedDeque<>();
    private final int maxSize;

    public LogBuffer(int maxSize) {
        this.maxSize = maxSize;
    }

    public LogBuffer() {
        this(5000);
    }

    public void add(LogEvent event) {
        buffer.addLast(event);
        while (buffer.size() > maxSize) {
            buffer.pollFirst();
        }
    }

    public List<LogEvent> getAll() {
        return new ArrayList<>(buffer);
    }

    public List<LogEvent> getFiltered(String moduleId, String level) {
        return getFiltered(moduleId, level, null, null, null);
    }

    /**
     * Get filtered log events with optional module, level, time range, and text search.
     *
     * @param moduleId filter by module ID (null = all)
     * @param level    filter by log level (null = all)
     * @param from     start of time range (null = no lower bound)
     * @param to       end of time range (null = no upper bound)
     * @param search   text substring search on message (null = no search)
     */
    public List<LogEvent> getFiltered(String moduleId, String level, Instant from, Instant to, String search) {
        return stream()
                .filter(e -> moduleId == null || moduleId.isEmpty() || moduleId.equals(e.moduleId()))
                .filter(e -> level == null || level.isEmpty() || level.equalsIgnoreCase(e.level()))
                .filter(e -> from == null || !e.timestamp().isBefore(from))
                .filter(e -> to == null || !e.timestamp().isAfter(to))
                .filter(e -> search == null || search.isEmpty() ||
                        e.message().toLowerCase().contains(search.toLowerCase()))
                .collect(Collectors.toList());
    }

    /**
     * Get the most recent N events.
     */
    public List<LogEvent> getRecent(int count) {
        List<LogEvent> all = getAll();
        int from = Math.max(0, all.size() - count);
        return all.subList(from, all.size());
    }

    public Stream<LogEvent> stream() {
        return buffer.stream();
    }

    public int size() {
        return buffer.size();
    }

    public void clear() {
        buffer.clear();
    }
}

