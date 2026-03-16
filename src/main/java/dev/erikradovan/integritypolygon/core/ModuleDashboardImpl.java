package dev.erikradovan.integritypolygon.core;

import com.google.gson.Gson;
import dev.erikradovan.integritypolygon.api.ModuleDashboard;
import dev.erikradovan.integritypolygon.web.websocket.RealtimeHandler;
import io.javalin.Javalin;
import io.javalin.http.Context;
import org.slf4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Per-module dashboard implementation. Handles:
 * <ul>
 *   <li>Extracting static dashboard files from the module JAR to a serve directory</li>
 *   <li>Registering module-scoped REST API endpoints under /api/modules/{id}/</li>
 *   <li>Serving the module's static dashboard files under /modules/{id}/</li>
 *   <li>Pushing real-time updates to the panel via WebSocket</li>
 * </ul>
 */
public class ModuleDashboardImpl implements ModuleDashboard {

    /**
     * Tracks which module IDs already have their static-file routes registered.
     * Prevents "Handler already exists" on hot-reload.
     */
    private static final Set<String> REGISTERED_STATIC = ConcurrentHashMap.newKeySet();

    /**
     * Shared mutable map of module dashboard directories. The Javalin lambdas
     * look up the current directory here at request time, so hot-reloads
     * are reflected immediately without re-registering routes.
     */
    private static final ConcurrentHashMap<String, Path> DASHBOARD_DIRS = new ConcurrentHashMap<>();

    private final String moduleId;
    private final Javalin app;
    private final RealtimeHandler realtimeHandler;
    private final Logger logger;
    private final Gson gson = new Gson();
    private volatile Path extractedDashboardDir;
    private final List<String> registeredPaths = new ArrayList<>();

    public ModuleDashboardImpl(String moduleId, Javalin app, RealtimeHandler realtimeHandler,
                               Path dashboardBaseDir, Logger logger) {
        this.moduleId = moduleId;
        this.app = app;
        this.realtimeHandler = realtimeHandler;
        this.logger = logger;
        this.extractedDashboardDir = dashboardBaseDir.resolve(moduleId);
    }

    /**
     * Extract dashboard static files from the module JAR into the serve directory.
     *
     * @param jarFile       the module JAR file
     * @param dashboardPath the path prefix inside the JAR (e.g., "web/")
     */
    public void extractDashboardFiles(File jarFile, String dashboardPath) {
        if (dashboardPath == null || dashboardPath.isEmpty()) return;

        // Normalize the path prefix
        String prefix = dashboardPath.endsWith("/") ? dashboardPath : dashboardPath + "/";

        try {
            Files.createDirectories(extractedDashboardDir);

            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    if (!entryName.startsWith(prefix) || entry.isDirectory()) continue;

                    // Relative path inside the dashboard
                    String relativePath = entryName.substring(prefix.length());
                    if (relativePath.isEmpty()) continue;

                    Path targetFile = extractedDashboardDir.resolve(relativePath);
                    Files.createDirectories(targetFile.getParent());

                    try (InputStream in = jar.getInputStream(entry)) {
                        Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }

            // Register a static file handler for this module's dashboard
            registerStaticFileHandler();
            DASHBOARD_DIRS.put(moduleId, extractedDashboardDir);
            logger.debug("Extracted dashboard for module '{}' to {}", moduleId, extractedDashboardDir);

        } catch (IOException e) {
            logger.error("Failed to extract dashboard for module '{}'", moduleId, e);
        }
    }

    private void registerStaticFileHandler() {
        // Only register Javalin routes once per module. On hot-reload, the volatile
        // extractedDashboardDir is swapped and the existing lambdas serve from the new dir.
        if (!REGISTERED_STATIC.add(moduleId)) {
            logger.debug("Static routes for '{}' already registered, reusing", moduleId);
            return;
        }

        String prefix = "/modules/" + moduleId + "/";

        // Serve root index
        app.get(prefix, ctx -> serveStaticFile(ctx, "index.html"));

        // Serve sub-paths using Javalin's <path-param> syntax
        app.get(prefix + "<filepath>", ctx -> {
            String requestedFile = ctx.pathParam("filepath");
            if (requestedFile.isEmpty()) {
                requestedFile = "index.html";
            }
            serveStaticFile(ctx, requestedFile);
        });
        registeredPaths.add(prefix);
    }

    private void serveStaticFile(io.javalin.http.Context ctx, String requestedFile) throws IOException {
        // Resolve current directory from shared map (supports hot-reload)
        Path dashDir = DASHBOARD_DIRS.getOrDefault(moduleId, extractedDashboardDir);
        Path filePath = dashDir.resolve(requestedFile).normalize();

        // Security: prevent path traversal
        if (!filePath.startsWith(dashDir)) {
            ctx.status(403).result("Forbidden");
            return;
        }

        if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
            ctx.contentType(getMimeType(filePath.toString()));
            ctx.result(Files.newInputStream(filePath));
        } else {
            // SPA fallback: serve index.html for unresolved paths
            Path indexPath = dashDir.resolve("index.html");
            if (Files.exists(indexPath)) {
                ctx.contentType("text/html");
                ctx.result(Files.newInputStream(indexPath));
            } else {
                ctx.status(404).result("Not found");
            }
        }
    }

    /**
     * Global map of registered API route delegates. Key = "METHOD /full/path".
     * Each delegate holds a mutable RouteHandler that gets swapped on reload.
     */
    private static final ConcurrentHashMap<String, RouteHandlerDelegate> API_DELEGATES = new ConcurrentHashMap<>();

    @Override
    public void get(String path, RouteHandler handler) {
        registerApiRoute("GET", path, handler, app::get);
    }

    @Override
    public void post(String path, RouteHandler handler) {
        registerApiRoute("POST", path, handler, app::post);
    }

    @Override
    public void put(String path, RouteHandler handler) {
        registerApiRoute("PUT", path, handler, app::put);
    }

    @Override
    public void delete(String path, RouteHandler handler) {
        registerApiRoute("DELETE", path, handler, app::delete);
    }

    private void registerApiRoute(String method, String path, RouteHandler handler,
                                  java.util.function.BiConsumer<String, io.javalin.http.Handler> registrar) {
        String fullPath = "/api/modules/" + moduleId + "/" + path;
        String key = method + " " + fullPath;

        RouteHandlerDelegate delegate = API_DELEGATES.get(key);
        if (delegate != null) {
            // Route already registered in Javalin — just swap the handler
            delegate.handler = handler;
            logger.debug("Swapped handler for {} {}", method, fullPath);
        } else {
            // First time — register with Javalin and create delegate
            delegate = new RouteHandlerDelegate(handler);
            API_DELEGATES.put(key, delegate);
            RouteHandlerDelegate finalDelegate = delegate;
            registrar.accept(fullPath, ctx -> finalDelegate.handler.handle(wrapContext(ctx)));
            registeredPaths.add(key);
        }
    }

    /** Mutable holder for a RouteHandler, allowing hot-swap on module reload. */
    private static class RouteHandlerDelegate {
        volatile RouteHandler handler;
        RouteHandlerDelegate(RouteHandler handler) { this.handler = handler; }
    }

    @Override
    public void pushUpdate(String event, Map<String, Object> data) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("moduleId", moduleId);
        payload.put("event", event);
        payload.putAll(data);
        realtimeHandler.broadcast("module_update", payload);
    }

    /**
     * Clean up extracted files. Called on module unload.
     */
    public void cleanup() {
        try {
            if (Files.exists(extractedDashboardDir)) {
                try (var stream = Files.walk(extractedDashboardDir)) {
                    stream.sorted(Comparator.reverseOrder())
                            .map(Path::toFile)
                            .forEach(File::delete);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to clean dashboard files for module '{}'", moduleId, e);
        }
    }

    /**
     * Wrap a Javalin {@link Context} into a framework {@link RequestContext}
     * so modules don't need to depend on Javalin.
     */
    private RequestContext wrapContext(Context ctx) {
        return new RequestContext() {
            @Override
            public String body() {
                return ctx.body();
            }

            @Override
            public String pathParam(String name) {
                return ctx.pathParam(name);
            }

            @Override
            public String queryParam(String name) {
                return ctx.queryParam(name);
            }

            @Override
            public String queryParam(String name, String defaultValue) {
                String val = ctx.queryParam(name);
                return val != null ? val : defaultValue;
            }

            @Override
            public String authenticatedUser() {
                String auth = ctx.header("Authorization");
                if (auth != null && auth.startsWith("Bearer ")) {
                    // The auth middleware already validated the token, so
                    // we just extract the subject. This is a simplified approach.
                    return auth.substring(7); // The caller can decode if needed
                }
                return null;
            }

            @Override
            public RequestContext status(int code) {
                ctx.status(code);
                return this;
            }

            @Override
            public void json(Object obj) {
                ctx.json(obj);
            }

            @Override
            public void result(String text) {
                ctx.result(text);
            }
        };
    }

    private String getMimeType(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".js")) return "application/javascript";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".jpg") || path.endsWith(".jpeg")) return "image/jpeg";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        if (path.endsWith(".woff2")) return "font/woff2";
        if (path.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }
}
