package dev.erikradovan.integritypolygon.web;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.ExtenderServiceImpl;
import dev.erikradovan.integritypolygon.core.LoadedModule;
import dev.erikradovan.integritypolygon.core.MetricsBuffer;
import dev.erikradovan.integritypolygon.core.MetricsServiceImpl;
import dev.erikradovan.integritypolygon.core.ModuleManager;
import dev.erikradovan.integritypolygon.logging.LogManager;
import dev.erikradovan.integritypolygon.web.auth.AuthManager;
import dev.erikradovan.integritypolygon.web.auth.SetupWizard;
import dev.erikradovan.integritypolygon.web.routes.*;
import dev.erikradovan.integritypolygon.web.websocket.RealtimeHandler;
import io.javalin.Javalin;
import io.javalin.http.HttpResponseException;
import io.javalin.http.staticfiles.Location;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/**
 * Embedded Javalin web server for the IntegrityPolygon management panel.
 * Provides a REST API, WebSocket for real-time log streaming, module dashboard
 * hosting, and static file serving for the main panel application.
 *
 * <p>Module dashboards are served under {@code /modules/{moduleId}/} with
 * their API endpoints under {@code /api/modules/{moduleId}/}. The main panel
 * discovers all module dashboards via {@code GET /api/dashboards} and renders
 * navigation entries in the sidebar.
 */
public class WebServer {

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/setup/status",
            "/api/auth/login",
            "/metrics"
    );

    /** Paths that require a valid JWT but are allowed even before setup is completed. */
    private static final Set<String> PRE_SETUP_PATHS = Set.of(
            "/api/auth/change-password",
            "/api/setup",
            "/api/health"
    );

    private final Javalin app;
    private final ConfigManager configManager;
    private final AuthManager authManager;
    private final RealtimeHandler realtimeHandler;
    private final ModuleManager moduleManager;
    private final StatusRoutes statusRoutes;
    private final MetricsServiceImpl metricsService;
    private final Logger logger;
    private final Path dataDirectory;

    public WebServer(ConfigManager configManager, ModuleManager moduleManager,
                     LogManager logManager, ProxyServer proxy, Logger logger,
                     MetricsServiceImpl metricsService, MetricsBuffer metricsBuffer,
                     Path dataDirectory) {
        this.configManager = configManager;
        this.moduleManager = moduleManager;
        this.logger = logger;
        this.authManager = new AuthManager(configManager);
        this.metricsService = metricsService;
        this.dataDirectory = dataDirectory;

        // Suppress noisy Javalin/Jetty startup logs
        silenceInternalLoggers();

        SetupWizard setupWizard = new SetupWizard(configManager, authManager);
        ModuleRoutes moduleRoutes = new ModuleRoutes(moduleManager, configManager);
        ConfigRoutes configRoutes = new ConfigRoutes(configManager);
        StatusRoutes statusRoutes = new StatusRoutes(proxy);
        this.statusRoutes = statusRoutes;
        MonitoringRoutes monitoringRoutes = new MonitoringRoutes(metricsBuffer, logManager);
        RepositoryRoutes repoRoutes = new RepositoryRoutes(configManager, moduleManager, logger);
        UserRoutes userRoutes = new UserRoutes(configManager);
        this.realtimeHandler = new RealtimeHandler(authManager, logManager, logger);

        this.app = Javalin.create(config -> {
            config.plugins.enableCors(cors -> cors.add(rule -> rule.anyHost()));
            config.staticFiles.add("/panel", Location.CLASSPATH);
            config.showJavalinBanner = false;

            // Configure Jetty with both HTTP and HTTPS connectors
            config.jetty.server(() -> {
                Server server = new Server();
                int port = configManager.getWebPort();
                String bind = configManager.getWebBind();

                // Always add HTTP connector
                ServerConnector httpConnector = new ServerConnector(server);
                httpConnector.setHost(bind);
                httpConnector.setPort(port);

                // Try to set up HTTPS on the same port + 1, or as the primary connector
                SslCertificateManager sslMgr = new SslCertificateManager(dataDirectory, logger);
                Path keystorePath = sslMgr.ensureKeystore();

                if (keystorePath != null) {
                    try {
                        SslContextFactory.Server sslContextFactory = new SslContextFactory.Server();
                        sslContextFactory.setKeyStorePath(keystorePath.toAbsolutePath().toString());
                        sslContextFactory.setKeyStorePassword(sslMgr.getKeystorePassword());
                        sslContextFactory.setKeyManagerPassword(sslMgr.getKeystorePassword());
                        sslContextFactory.setSniRequired(false);

                        HttpConfiguration httpsConfig = new HttpConfiguration();
                        httpsConfig.setSecureScheme("https");
                        httpsConfig.setSecurePort(port);
                        httpsConfig.addCustomizer(new SecureRequestCustomizer(false));

                        ServerConnector httpsConnector = new ServerConnector(server,
                                new SslConnectionFactory(sslContextFactory, "http/1.1"),
                                new HttpConnectionFactory(httpsConfig));
                        httpsConnector.setHost(bind);
                        httpsConnector.setPort(port);

                        // Use HTTPS as the only connector on the configured port
                        server.setConnectors(new Connector[]{httpsConnector});
                        logger.info("SSL enabled — web panel will serve HTTPS on port {}", port);
                    } catch (Exception e) {
                        logger.warn("SSL setup failed, falling back to HTTP: {}", e.getMessage());
                        server.setConnectors(new Connector[]{httpConnector});
                    }
                } else {
                    server.setConnectors(new Connector[]{httpConnector});
                }

                return server;
            });
        });

        registerAuthMiddleware();
        registerPublicRoutes(setupWizard);
        registerProtectedRoutes(moduleRoutes, configRoutes, statusRoutes, monitoringRoutes, repoRoutes, userRoutes);
        registerDashboardManifest();
        registerWebSocket();

        // Start live status ticker for WebSocket clients
        realtimeHandler.startStatusTicker(proxy::getPlayerCount, System.currentTimeMillis());

        // Give the module manager access to Javalin so modules can register dashboard routes
        moduleManager.setWebComponents(app, realtimeHandler);
    }

    private void registerAuthMiddleware() {
        // Auth for API routes
        app.before("/api/*", ctx -> {
            String path = ctx.path();

            // Fully public — no auth needed
            if (PUBLIC_PATHS.contains(path)) {
                return;
            }

            // Pre-setup paths require a valid JWT but NOT completed setup
            if (PRE_SETUP_PATHS.contains(path)) {
                requireValidToken(ctx);
                return;
            }

            // Everything else requires completed setup + valid JWT
            if (!configManager.isSetupCompleted()) {
                throw new HttpResponseException(503, "Setup not completed");
            }

            requireValidToken(ctx);
        });

        // Auth for module dashboard static files (they may contain sensitive config UIs)
        app.before("/modules/*", ctx -> {
            if (!configManager.isSetupCompleted()) {
                throw new HttpResponseException(503, "Setup not completed");
            }

            String authHeader = ctx.header("Authorization");
            // Allow cookie-based auth for static files loaded by the browser
            String tokenParam = ctx.queryParam("token");
            String token = null;

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            } else if (tokenParam != null) {
                token = tokenParam;
            }

            if (token == null || authManager.validateToken(token).isEmpty()) {
                throw new HttpResponseException(401, "Unauthorized");
            }
        });
    }

    private void requireValidToken(io.javalin.http.Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new HttpResponseException(401, "Missing or invalid Authorization header");
        }
        String token = authHeader.substring(7);
        if (authManager.validateToken(token).isEmpty()) {
            throw new HttpResponseException(401, "Invalid or expired token");
        }
    }

    private void registerPublicRoutes(SetupWizard setupWizard) {
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "version", "2.0.0")));
        app.get("/api/setup/status", setupWizard::handleSetupStatus);
        app.post("/api/auth/login", setupWizard::handleLogin);

        // Prometheus metrics endpoint (unauthenticated for scraper access)
        app.get("/metrics", ctx -> {
            ctx.contentType("text/plain; version=0.0.4; charset=utf-8");
            ctx.result(metricsService.scrape());
        });

        // These require a valid JWT but are allowed before setup is completed
        app.post("/api/auth/change-password", setupWizard::handleChangePassword);
        app.post("/api/setup", setupWizard::handleSetup);
    }

    private void registerProtectedRoutes(ModuleRoutes moduleRoutes, ConfigRoutes configRoutes,
                                         StatusRoutes statusRoutes, MonitoringRoutes monitoringRoutes,
                                         RepositoryRoutes repoRoutes, UserRoutes userRoutes) {
        app.get("/api/modules", moduleRoutes::listModules);
        app.post("/api/modules/{id}/load", moduleRoutes::loadModule);
        app.post("/api/modules/{id}/unload", moduleRoutes::unloadModule);
        app.post("/api/modules/{id}/reload", moduleRoutes::reloadModule);
        app.post("/api/modules/{id}/enable", moduleRoutes::enableModule);
        app.post("/api/modules/{id}/disable", moduleRoutes::disableModule);

        app.get("/api/config", configRoutes::getConfig);
        app.put("/api/config", configRoutes::updateConfig);
        app.patch("/api/config/ports", configRoutes::updatePorts);
        app.get("/api/config/modules/{id}", configRoutes::getModuleConfig);
        app.put("/api/config/modules/{id}", configRoutes::updateModuleConfig);

        app.get("/api/status", statusRoutes::getStatus);
        app.get("/api/extenders", statusRoutes::getExtenders);

        // Monitoring (replaces old /api/logs)
        app.get("/api/monitoring/series", monitoringRoutes::getSeries);
        app.get("/api/monitoring/latest", monitoringRoutes::getLatest);
        app.get("/api/monitoring/logs", monitoringRoutes::getLogs);

        app.get("/api/repository", repoRoutes::listAvailable);
        app.post("/api/repository/{id}/install", repoRoutes::installModule);
        app.post("/api/repository/{id}/uninstall", repoRoutes::uninstallModule);

        app.get("/api/users", userRoutes::listUsers);
        app.post("/api/users", userRoutes::addUser);
        app.put("/api/users/{username}", userRoutes::updateUser);
        app.delete("/api/users/{username}", userRoutes::removeUser);
    }

    /**
     * GET /api/dashboards — returns a manifest of all loaded modules that have
     * dashboards. The main panel JS uses this to build the sidebar navigation.
     */
    private void registerDashboardManifest() {
        app.get("/api/dashboards", ctx -> {
            List<Map<String, Object>> dashboards = new ArrayList<>();

            for (var entry : moduleManager.getLoadedModules().entrySet()) {
                LoadedModule loaded = entry.getValue();
                if (!loaded.descriptor().hasDashboard()) continue;

                Map<String, Object> info = new LinkedHashMap<>();
                info.put("id", loaded.descriptor().id());
                info.put("name", loaded.descriptor().name());
                info.put("version", loaded.descriptor().version());
                info.put("description", loaded.descriptor().description());
                info.put("authors", loaded.descriptor().authors());
                info.put("dashboardUrl", "/modules/" + loaded.descriptor().id() + "/");
                info.put("apiBaseUrl", "/api/modules/" + loaded.descriptor().id() + "/");
                dashboards.add(info);
            }

            ctx.json(Map.of("dashboards", dashboards));
        });
    }

    private void registerWebSocket() {
        app.ws("/ws/live", realtimeHandler);
    }

    /**
     * @return the realtime WebSocket handler, for use by AlertService
     */
    public RealtimeHandler getRealtimeHandler() {
        return realtimeHandler;
    }

    /**
     * Wire up the ExtenderServiceImpl so StatusRoutes can expose backend server data.
     */
    public void setExtenderService(ExtenderServiceImpl extenderService) {
        statusRoutes.setExtenderService(extenderService);
    }

    /**
     * Start the web server on the configured port.
     * Connectors (HTTP/HTTPS) are configured in the Jetty server factory.
     */
    public void start() {
        app.start();
    }

    /**
     * Stop the web server and clean up WebSocket sessions.
     */
    public void stop() {
        realtimeHandler.shutdown();
        try {
            app.stop();
        } catch (Exception e) {
            // Jetty may throw NoClassDefFoundError for inner classes if the
            // plugin JAR was overwritten at runtime (hot-reload). Safe to ignore.
        }
    }

    /**
     * Suppress noisy Javalin/Jetty startup logs.
     * Uses system properties which Jetty's StdErrLog checks before outputting.
     * Both original and relocated package names are set for safety.
     */
    private void silenceInternalLoggers() {
        // Jetty honours these system properties for its internal logging
        String[] prefixes = {
                "org.eclipse.jetty",
                "dev.erikradovan.integritypolygon.libs.jetty",
                "io.javalin",
                "dev.erikradovan.integritypolygon.libs.javalin"
        };
        for (String prefix : prefixes) {
            System.setProperty(prefix + ".LEVEL", "WARN");
            System.setProperty(prefix + ".log.LEVEL", "WARN");
        }

        // Also try reflection on each concrete logger (works with Logback/Log4j2)
        for (String prefix : prefixes) {
            try {
                org.slf4j.Logger l = LoggerFactory.getLogger(prefix);
                var clazz = l.getClass();
                // Try ch.qos.logback.classic.Logger#setLevel
                for (var m : clazz.getMethods()) {
                    if ("setLevel".equals(m.getName()) && m.getParameterCount() == 1) {
                        Class<?> levelClass = m.getParameterTypes()[0];
                        // Try to get WARN level from the enum/class
                        try {
                            Object warnLevel = levelClass.getField("WARN").get(null);
                            m.invoke(l, warnLevel);
                        } catch (NoSuchFieldException ignored2) {
                            try {
                                Object warnLevel = levelClass.getMethod("valueOf", String.class).invoke(null, "WARN");
                                m.invoke(l, warnLevel);
                            } catch (Exception ignored3) {}
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}
