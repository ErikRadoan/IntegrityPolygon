package dev.erikradovan.integritypolygon.api;

/**
 * Service for exposing Prometheus-compatible metrics.
 * <p>
 * Modules use this to register counters, gauges, and histograms that are
 * automatically exported via the {@code /metrics} HTTP endpoint in Prometheus
 * exposition format. External Prometheus/VictoriaMetrics/Grafana Agent instances
 * scrape this endpoint.
 * <p>
 * Obtain via: {@code context.getServiceRegistry().get(MetricsService.class)}
 */
public interface MetricsService {

    /**
     * Increment a counter metric.
     *
     * @param name   metric name (e.g., "ip_antibot_blocked_total")
     * @param help   metric description (used on first registration)
     * @param labels label key-value pairs (e.g., "module", "anti-bot")
     * @param value  amount to increment
     */
    void counterInc(String name, String help, String[] labelNames, String[] labelValues, double value);

    /**
     * Increment a counter by 1.
     */
    default void counterInc(String name, String help, String[] labelNames, String[] labelValues) {
        counterInc(name, help, labelNames, labelValues, 1.0);
    }

    /**
     * Set a gauge metric value.
     *
     * @param name   metric name (e.g., "ip_server_tps")
     * @param help   metric description
     * @param labels label key-value pairs
     * @param value  the gauge value
     */
    void gaugeSet(String name, String help, String[] labelNames, String[] labelValues, double value);

    /**
     * Observe a value in a histogram (e.g., latency measurements).
     *
     * @param name   metric name
     * @param help   metric description
     * @param labels label key-value pairs
     * @param value  the observed value
     */
    void histogramObserve(String name, String help, String[] labelNames, String[] labelValues, double value);

    /**
     * Dump all registered metrics in Prometheus exposition format (text/plain).
     *
     * @return the metrics text
     */
    String scrape();
}

