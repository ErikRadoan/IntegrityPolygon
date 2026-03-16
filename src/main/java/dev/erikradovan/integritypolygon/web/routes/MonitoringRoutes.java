package dev.erikradovan.integritypolygon.web.routes;

import dev.erikradovan.integritypolygon.core.MetricsBuffer;
import dev.erikradovan.integritypolygon.logging.LogEvent;
import dev.erikradovan.integritypolygon.logging.LogManager;
import io.javalin.http.Context;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API routes for the built-in monitoring dashboard.
 * <p>
 * Replaces both the old LogRoutes and the external Grafana/Prometheus approach
 * with a zero-config, zero-port, built-in monitoring experience.
 * <p>
 * Endpoints:
 * <ul>
 *   <li>{@code GET /api/monitoring/series} — time-series data for charts</li>
 *   <li>{@code GET /api/monitoring/latest} — latest value for all metrics</li>
 *   <li>{@code GET /api/monitoring/logs} — log events with filtering</li>
 * </ul>
 * <p>
 * Power users who want Grafana can still scrape {@code GET /metrics}
 * (Prometheus exposition format) on the same port — no extra services needed.
 */
public class MonitoringRoutes {

    private final MetricsBuffer metricsBuffer;
    private final LogManager logManager;

    public MonitoringRoutes(MetricsBuffer metricsBuffer, LogManager logManager) {
        this.metricsBuffer = metricsBuffer;
        this.logManager = logManager;
    }

    /**
     * GET /api/monitoring/series?names=proxy.players,proxy.cpu,...
     * Returns time-series data for the requested metric names.
     * If no names param is provided, returns all available series.
     */
    public void getSeries(Context ctx) {
        String namesParam = ctx.queryParam("names");
        Collection<String> names;
        if (namesParam != null && !namesParam.isBlank()) {
            names = Arrays.asList(namesParam.split(","));
        } else {
            names = metricsBuffer.getSeriesNames();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : names) {
            List<MetricsBuffer.DataPoint> points = metricsBuffer.getSeries(name.trim());
            List<List<Object>> pointData = points.stream()
                    .map(p -> List.<Object>of(p.timestamp(), Math.round(p.value() * 100.0) / 100.0))
                    .collect(Collectors.toList());
            result.put(name.trim(), pointData);
        }

        ctx.json(Map.of(
                "series", result,
                "available", new ArrayList<>(metricsBuffer.getSeriesNames())
        ));
    }

    /**
     * GET /api/monitoring/latest
     * Returns the most recent value for every tracked metric.
     */
    public void getLatest(Context ctx) {
        ctx.json(metricsBuffer.getAllLatest());
    }

    /**
     * GET /api/monitoring/logs — log events with optional filtering.
     * Query params: module, level, from (ISO timestamp), to (ISO timestamp),
     *               search (text substring), limit (default 200)
     */
    public void getLogs(Context ctx) {
        String moduleFilter = ctx.queryParam("module");
        String levelFilter = ctx.queryParam("level");
        String fromParam = ctx.queryParam("from");
        String toParam = ctx.queryParam("to");
        String searchParam = ctx.queryParam("search");
        int limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(200);

        Instant from = null;
        Instant to = null;
        try {
            if (fromParam != null && !fromParam.isEmpty()) from = Instant.parse(fromParam);
        } catch (Exception ignored) {
        }
        try {
            if (toParam != null && !toParam.isEmpty()) to = Instant.parse(toParam);
        } catch (Exception ignored) {
        }

        List<LogEvent> logs = logManager.getBuffer().getFiltered(
                moduleFilter, levelFilter, from, to, searchParam);

        // Apply limit (most recent)
        if (logs.size() > limit) {
            logs = logs.subList(logs.size() - limit, logs.size());
        }

        List<Map<String, Object>> result = logs.stream().map(e -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("moduleId", e.moduleId());
            map.put("level", e.level());
            map.put("tag", e.tag());
            map.put("message", e.message());
            map.put("timestamp", e.timestamp().toString());
            return map;
        }).collect(Collectors.toList());

        ctx.json(Map.of(
                "total", logManager.getBuffer().size(),
                "returned", result.size(),
                "logs", result
        ));
    }
}

