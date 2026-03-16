package dev.erikradovan.integritypolygon.api;

import java.util.Map;

/**
 * Module-scoped dashboard registration interface. Each module receives its own
 * instance via {@link ModuleContext#getDashboard()}.
 *
 * <p>Modules ship their dashboard UI as static files inside the JAR under a
 * configurable path (declared in {@code module.json} as {@code "dashboard": "web/"}).
 * The framework extracts these files and serves them at
 * {@code /modules/{moduleId}/} on the web panel.
 *
 * <p>Modules register custom REST API endpoints that their dashboard JS calls.
 * These are mounted under {@code /api/modules/{moduleId}/} and are automatically
 * protected by the framework's JWT auth middleware.
 *
 * <p>Example in a module's {@code onEnable}:
 * <pre>{@code
 * ModuleDashboard dashboard = context.getDashboard();
 *
 * // Register a GET endpoint: /api/modules/antibot/stats
 * dashboard.get("stats", ctx -> {
 *     ctx.json(Map.of("blocked", blockedCount, "allowed", allowedCount));
 * });
 *
 * // Register a POST endpoint: /api/modules/antibot/config
 * dashboard.post("config", ctx -> {
 *     JsonObject body = gson.fromJson(ctx.body(), JsonObject.class);
 *     // update config...
 *     ctx.json(Map.of("success", true));
 * });
 *
 * // Push real-time data to the dashboard via WebSocket
 * dashboard.pushUpdate("stats", Map.of("blocked", blockedCount));
 * }</pre>
 *
 * <p>The main panel auto-discovers all module dashboards and renders them
 * as navigation entries in the sidebar.
 */
public interface ModuleDashboard {

    /**
     * Register a GET endpoint under {@code /api/modules/{moduleId}/{path}}.
     *
     * @param path    the relative path (e.g., "stats", "config/rules")
     * @param handler the request handler
     */
    void get(String path, RouteHandler handler);

    /**
     * Register a POST endpoint under {@code /api/modules/{moduleId}/{path}}.
     *
     * @param path    the relative path
     * @param handler the request handler
     */
    void post(String path, RouteHandler handler);

    /**
     * Register a PUT endpoint under {@code /api/modules/{moduleId}/{path}}.
     *
     * @param path    the relative path
     * @param handler the request handler
     */
    void put(String path, RouteHandler handler);

    /**
     * Register a DELETE endpoint under {@code /api/modules/{moduleId}/{path}}.
     *
     * @param path    the relative path
     * @param handler the request handler
     */
    void delete(String path, RouteHandler handler);

    /**
     * Push a real-time update to all WebSocket clients listening to this module.
     * The panel JS receives this as a WebSocket message with
     * {@code {"type": "module_update", "moduleId": "...", "event": "...", "data": {...}}}.
     *
     * @param event the event name (e.g., "stats", "alert")
     * @param data  the payload data
     */
    void pushUpdate(String event, Map<String, Object> data);

    /**
     * Abstraction over HTTP request/response context, so modules don't need to
     * import Javalin directly. This keeps the API clean and module JARs lightweight.
     */
    interface RequestContext {

        /** @return the raw request body as a string */
        String body();

        /** @return a path parameter value */
        String pathParam(String name);

        /** @return a query parameter value, or null */
        String queryParam(String name);

        /** @return a query parameter value with a default */
        String queryParam(String name, String defaultValue);

        /** @return the Authorization header's JWT subject (username), or null */
        String authenticatedUser();

        /** Set the response status code */
        RequestContext status(int code);

        /** Send a JSON response (serializes the object to JSON) */
        void json(Object obj);

        /** Send a plain text response */
        void result(String text);
    }

    /**
     * Functional interface for handling HTTP requests.
     */
    @FunctionalInterface
    interface RouteHandler {
        void handle(RequestContext ctx) throws Exception;
    }
}
