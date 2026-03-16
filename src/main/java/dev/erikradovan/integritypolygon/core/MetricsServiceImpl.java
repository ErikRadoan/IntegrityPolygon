package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.MetricsService;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.common.TextFormat;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prometheus-backed MetricsService implementation.
 * <p>
 * Lazily creates and caches Prometheus collectors (Counter, Gauge, Histogram)
 * keyed by metric name. All collectors are registered in a dedicated
 * {@link CollectorRegistry} so they don't pollute the JVM-wide default registry.
 * <p>
 * The {@link #scrape()} method produces Prometheus text exposition format
 * which is served on {@code GET /metrics} by the web server.
 */
public class MetricsServiceImpl implements MetricsService {

    private final CollectorRegistry registry = new CollectorRegistry();
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Gauge> gauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Histogram> histograms = new ConcurrentHashMap<>();

    @Override
    public void counterInc(String name, String help, String[] labelNames, String[] labelValues, double value) {
        Counter counter = counters.computeIfAbsent(name, n ->
                Counter.build().name(n).help(help).labelNames(labelNames).register(registry));
        counter.labels(labelValues).inc(value);
    }

    @Override
    public void gaugeSet(String name, String help, String[] labelNames, String[] labelValues, double value) {
        Gauge gauge = gauges.computeIfAbsent(name, n ->
                Gauge.build().name(n).help(help).labelNames(labelNames).register(registry));
        gauge.labels(labelValues).set(value);
    }

    @Override
    public void histogramObserve(String name, String help, String[] labelNames, String[] labelValues, double value) {
        Histogram histogram = histograms.computeIfAbsent(name, n ->
                Histogram.build().name(n).help(help).labelNames(labelNames).register(registry));
        histogram.labels(labelValues).observe(value);
    }

    @Override
    public String scrape() {
        try {
            StringWriter writer = new StringWriter();
            TextFormat.write004(writer, registry.metricFamilySamples());
            return writer.toString();
        } catch (IOException e) {
            return "# error scraping metrics: " + e.getMessage() + "\n";
        }
    }

    /**
     * @return the underlying Prometheus registry for custom integrations
     */
    public CollectorRegistry getRegistry() {
        return registry;
    }
}

