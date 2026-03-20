package dev.erikradovan.integritypolygon.web;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.ExtenderServiceImpl;
import dev.erikradovan.integritypolygon.core.LoadedModule;
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
            "/api/auth/logout"
    );

    /** Paths that require auth but are allowed even before setup is completed. */
    private static final Set<String> PRE_SETUP_PATHS = Set.of(
            "/api/auth/change-password",
            "/api/auth/me",
            "/api/auth/account",
            "/api/setup",
            "/api/health"
    );

    private final Javalin app;
    private final ConfigManager configManager;
    private final AuthManager authManager;
    private final RealtimeHandler realtimeHandler;
    private final ModuleManager moduleManager;
    private final StatusRoutes statusRoutes;
    private final Logger logger;
    private final Path dataDirectory;

    public WebServer(ConfigManager configManager, ModuleManager moduleManager,
                     LogManager logManager, ProxyServer proxy, Logger logger,
                     Path dataDirectory) {
        this.configManager = configManager;
        this.moduleManager = moduleManager;
        this.logger = logger;
        this.authManager = new AuthManager(configManager);
        this.dataDirectory = dataDirectory;

        // Suppress noisy Javalin/Jetty startup logs
        silenceInternalLoggers();

        SetupWizard setupWizard = new SetupWizard(configManager, authManager);
        ModuleRoutes moduleRoutes = new ModuleRoutes(moduleManager, configManager);
        ConfigRoutes configRoutes = new ConfigRoutes(configManager);
        StatusRoutes statusRoutes = new StatusRoutes(proxy);
        this.statusRoutes = statusRoutes;
        RepositoryRoutes repoRoutes = new RepositoryRoutes(configManager, moduleManager, logger);
        UserRoutes userRoutes = new UserRoutes(configManager, authManager);
        this.realtimeHandler = new RealtimeHandler(authManager, logManager, logger);

        this.app = Javalin.create(config -> {
            config.staticFiles.add("/panel", Location.CLASSPATH);
            config.showJavalinBanner = false;
            config.jsonMapper(new GsonJsonMapper());

            // Configure Jetty with HTTPS connector only
            config.jetty.server(() -> {
                Server server = new Server();
                int port = configManager.getWebPort();
                String bind = configManager.getWebBind();

                SslCertificateManager sslMgr = new SslCertificateManager(dataDirectory, logger);
                Path keystorePath = sslMgr.ensureKeystore();
                if (keystorePath == null) {
                    throw new RuntimeException("HTTPS keystore setup failed; refusing to start without TLS");
                }

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
                server.setConnectors(new Connector[]{httpsConnector});

                return server;
            });
        });

        // Centralized logging for uncaught route/middleware errors.
        app.exception(Exception.class, (e, ctx) -> {
            logger.error("Web request failed: method={} path={} origin={} host={}",
                    ctx.req().getMethod(), ctx.path(), ctx.header("Origin"), ctx.host(), e);
            if (!ctx.res().isCommitted()) {
                ctx.status(500).json(Map.of("error", "Internal server error", "path", ctx.path()));
            }
        });

        registerAuthMiddleware();
        registerPublicRoutes(setupWizard);
        registerProtectedRoutes(moduleRoutes, configRoutes, statusRoutes, repoRoutes, userRoutes);
        registerDashboardManifest();
        registerWebSocket();

        // Start live status ticker for WebSocket clients
        realtimeHandler.startStatusTicker(proxy::getPlayerCount, System.currentTimeMillis());

        // Give the module manager access to Javalin so modules can register dashboard routes
        moduleManager.setWebComponents(app, realtimeHandler);
    }

    private void registerAuthMiddleware() {
        app.before(ctx -> ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains"));

        // Auth for API routes
        app.before("/api/*", ctx -> {
            String path = ctx.path();

            // Fully public — no auth needed
            if (PUBLIC_PATHS.contains(path)) {
                return;
            }

            // Pre-setup paths require auth but NOT completed setup
            if (PRE_SETUP_PATHS.contains(path)) {
                requireValidToken(ctx);
                requireSameOriginForMutatingRequests(ctx);
                return;
            }

            // Everything else requires completed setup + auth
            if (!configManager.isSetupCompleted()) {
                throw new HttpResponseException(503, "Setup not completed");
            }

            requireValidToken(ctx);
            requireSameOriginForMutatingRequests(ctx);
            enforceRolePolicy(ctx, path);
        });

        // Auth for module dashboard static files (they may contain sensitive config UIs)
        app.before("/modules/*", ctx -> {
            if (!configManager.isSetupCompleted()) {
                throw new HttpResponseException(503, "Setup not completed");
            }

            String token = ctx.cookie("ip_auth");
            if (token == null || authManager.validateSession(token).isEmpty()) {
                throw new HttpResponseException(401, "Unauthorized");
            }
            // HTTPS-only deployment should always advertise HSTS.
            ctx.header("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        });
    }

    private AuthManager.SessionRecord requireValidToken(io.javalin.http.Context ctx) {
        String token = ctx.cookie("ip_auth");
        if (token == null || token.isBlank()) {
            throw new HttpResponseException(401, "Missing auth session");
        }
        Optional<AuthManager.SessionRecord> decoded = authManager.validateSession(token);
        if (decoded.isEmpty()) {
            throw new HttpResponseException(401, "Invalid or expired token");
        }
        AuthManager.SessionRecord session = decoded.get();
        ctx.attribute("auth.username", session.username());
        ctx.attribute("auth.role", session.role());
        return session;
    }

    private void requireRole(io.javalin.http.Context ctx, String... allowedRoles) {
        String role = ctx.attribute("auth.role");
        if (role == null) {
            throw new HttpResponseException(401, "Unauthorized");
        }
        for (String allowed : allowedRoles) {
            if (allowed.equalsIgnoreCase(role)) {
                return;
            }
        }
        throw new HttpResponseException(403, "Forbidden");
    }

    private void requireSameOriginForMutatingRequests(io.javalin.http.Context ctx) {
        String method = ctx.req().getMethod();
        if (!("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method))) {
            return;
        }

        String expectedOrigin = ctx.scheme() + "://" + ctx.host();
        String origin = ctx.header("Origin");
        if (origin == null || !origin.equalsIgnoreCase(expectedOrigin)) {
            logger.warn("Blocked cross-origin mutating request: method={} path={} origin={} expected={}",
                    method, ctx.path(), origin, expectedOrigin);
            throw new HttpResponseException(403, "Cross-origin request blocked");
        }
    }

    private void enforceRolePolicy(io.javalin.http.Context ctx, String path) {
        String role = ctx.attribute("auth.role");
        if ("admin".equalsIgnoreCase(role)) {
            return;
        }

        boolean moderatorAllowed = path.equals("/api/health")
                || path.equals("/api/status")
                || path.equals("/api/dashboards")
                || path.startsWith("/api/modules/");

        if ("moderator".equalsIgnoreCase(role) && moderatorAllowed && !isModuleManagementPath(path)) {
            return;
        }

        throw new HttpResponseException(403, "Forbidden");
    }

    private boolean isModuleManagementPath(String path) {
        if ("/api/modules".equals(path)) {
            return true;
        }
        String[] parts = path.split("/");
        if (parts.length != 5) {
            return false;
        }
        if (!"api".equals(parts[1]) || !"modules".equals(parts[2])) {
            return false;
        }
        return Set.of("load", "unload", "reload", "enable", "disable").contains(parts[4]);
    }

    private void registerPublicRoutes(SetupWizard setupWizard) {
        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok", "version", "2.0.0")));
        app.get("/api/setup/status", ctx -> {
            try {
                setupWizard.handleSetupStatus(ctx);
            } catch (Exception e) {
                logger.error("Failed to serve /api/setup/status", e);
                throw e;
            }
        });
        app.post("/api/auth/login", setupWizard::handleLogin);
        app.post("/api/auth/logout", setupWizard::handleLogout);
        app.get("/api/auth/me", setupWizard::handleMe);
        app.patch("/api/auth/account", setupWizard::handleRenameAccount);

        // These require auth but are allowed before setup is completed
        app.post("/api/auth/change-password", setupWizard::handleChangePassword);
        app.post("/api/setup", setupWizard::handleSetup);
    }

    private void registerProtectedRoutes(ModuleRoutes moduleRoutes, ConfigRoutes configRoutes,
                                         StatusRoutes statusRoutes,
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
        app.get("/api/config/db/export", configRoutes::exportConfigDatabase);
        app.post("/api/config/db/import", configRoutes::importConfigDatabase);
        app.get("/api/config/data/export", configRoutes::exportDataBackup);
        app.post("/api/config/data/import", configRoutes::importDataBackup);

        app.get("/api/status", statusRoutes::getStatus);
        app.get("/api/extenders", statusRoutes::getExtenders);

        app.get("/api/repository", repoRoutes::listAvailable);
        app.post("/api/repository/{id}/install", repoRoutes::installModule);
        app.post("/api/repository/{id}/uninstall", repoRoutes::uninstallModule);

        app.get("/api/users", ctx -> {
            requireRole(ctx, "admin");
            userRoutes.listUsers(ctx);
        });
        app.post("/api/users", ctx -> {
            requireRole(ctx, "admin");
            userRoutes.addUser(ctx);
        });
        app.put("/api/users/{username}", ctx -> {
            requireRole(ctx, "admin");
            userRoutes.updateUser(ctx);
        });
        app.delete("/api/users/{username}", ctx -> {
            requireRole(ctx, "admin");
            userRoutes.removeUser(ctx);
        });
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
        String[] prefixes = {
                "dev.erikradovan.integritypolygon.libs.javalin",
                "dev.erikradovan.integritypolygon.libs.jetty",
                "io.javalin",
                "org.eclipse.jetty"
        };

        // ── Log4j2 (Velocity's actual logging backend) ──────────────────────────
        // Never import Log4j2 directly — it may be in Velocity's classloader.
        // Access everything through reflection to stay classloader-safe.
        try {
            Class<?> logManagerClass  = Class.forName("org.apache.logging.log4j.LogManager");
            Class<?> levelClass       = Class.forName("org.apache.logging.log4j.Level");
            Class<?> loggerClass      = Class.forName("org.apache.logging.log4j.core.Logger");
            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");

            Object errorLevel = levelClass.getField("ERROR").get(null);

            java.lang.reflect.Method setLevel = configuratorClass.getMethod(
                    "setLevel", String.class, levelClass);

            for (String prefix : prefixes) {
                setLevel.invoke(null, prefix, errorLevel);
            }
            return;
        } catch (Throwable ignored) {
            // Log4j2 not accessible — fall through
        }

        // ── System-property fallback (last resort) ──────────────────────────────
        System.setProperty("org.eclipse.jetty.util.log.class",
                "org.eclipse.jetty.util.log.StdErrLog");
        for (String prefix : prefixes) {
            System.setProperty(prefix + ".LEVEL", "ERROR");
        }
    }
}
