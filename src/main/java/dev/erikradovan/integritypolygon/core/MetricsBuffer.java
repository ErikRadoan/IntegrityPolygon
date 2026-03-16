package dev.erikradovan.integritypolygon.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory ring-buffer for time-series metrics data.
 * <p>
 * Stores metric snapshots (timestamp + value) keyed by metric name.
 * Each series retains at most {@code maxPoints} data points (default 360 = 1 hour at 10s intervals).
 * <p>
 * Used by the built-in monitoring dashboard to render charts without
 * any external dependency (no Prometheus, no Grafana, no extra ports).
 * Power users who want external monitoring can still scrape {@code /metrics}.
 */
public class MetricsBuffer {

    private static final int DEFAULT_MAX_POINTS = 360; // 1 hour at 10s intervals

    private final int maxPoints;
    private final ConcurrentHashMap<String, Deque<DataPoint>> series = new ConcurrentHashMap<>();

    public MetricsBuffer() {
        this(DEFAULT_MAX_POINTS);
    }

    public MetricsBuffer(int maxPoints) {
        this.maxPoints = maxPoints;
    }

    /**
     * Record a data point for a named metric series.
     */
    public void record(String seriesName, double value) {
        Deque<DataPoint> deque = series.computeIfAbsent(seriesName, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new DataPoint(System.currentTimeMillis(), value));
        while (deque.size() > maxPoints) {
            deque.pollFirst();
        }
    }

    /**
     * Get all data points for a series (oldest first).
     */
    public List<DataPoint> getSeries(String seriesName) {
        Deque<DataPoint> deque = series.get(seriesName);
        if (deque == null) return List.of();
        return new ArrayList<>(deque);
    }

    /**
     * Get the latest value for a series, or NaN if no data.
     */
    public double getLatest(String seriesName) {
        Deque<DataPoint> deque = series.get(seriesName);
        if (deque == null || deque.isEmpty()) return Double.NaN;
        return deque.peekLast().value();
    }

    /**
     * Get all series names.
     */
    public Set<String> getSeriesNames() {
        return Set.copyOf(series.keySet());
    }

    /**
     * Get a snapshot of all latest values (one per series).
     */
    public Map<String, Double> getAllLatest() {
        Map<String, Double> result = new LinkedHashMap<>();
        series.forEach((name, deque) -> {
            DataPoint last = deque.peekLast();
            if (last != null) result.put(name, last.value());
        });
        return result;
    }

    /**
     * Get multiple series as a map of name → data points, for bulk chart rendering.
     */
    public Map<String, List<DataPoint>> getMultipleSeries(Collection<String> names) {
        Map<String, List<DataPoint>> result = new LinkedHashMap<>();
        for (String name : names) {
            result.put(name, getSeries(name));
        }
        return result;
    }

    public record DataPoint(long timestamp, double value) {}
}

