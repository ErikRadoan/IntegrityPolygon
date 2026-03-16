package dev.erikradovan.integritypolygon;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.erikradovan.integritypolygon.api.AlertService;
import dev.erikradovan.integritypolygon.api.ExtenderService;
import dev.erikradovan.integritypolygon.api.HttpService;
import dev.erikradovan.integritypolygon.api.MetricsService;
import dev.erikradovan.integritypolygon.config.ConfigManager;
import dev.erikradovan.integritypolygon.core.AlertServiceImpl;
import dev.erikradovan.integritypolygon.core.ExtenderServiceImpl;
import dev.erikradovan.integritypolygon.core.HttpServiceImpl;
import dev.erikradovan.integritypolygon.core.MetricsCollector;
import dev.erikradovan.integritypolygon.core.MetricsServiceImpl;
import dev.erikradovan.integritypolygon.core.MetricsBuffer;
import dev.erikradovan.integritypolygon.core.ModuleManager;
import dev.erikradovan.integritypolygon.core.ModuleWatcher;
import dev.erikradovan.integritypolygon.core.ServiceRegistryImpl;
import dev.erikradovan.integritypolygon.logging.LogManager;
import dev.erikradovan.integritypolygon.messaging.ExtenderSocketServer;
import dev.erikradovan.integritypolygon.web.WebServer;
import dev.erikradovan.integritypolygon.web.auth.AuthManager;
import dev.erikradovan.integritypolygon.web.auth.SetupWizard;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Main Velocity plugin entry point for IntegrityPolygon.
 * <p>
 * Bootstraps the service registry, configuration, module manager,
 * embedded web server, and optional hot-reload watcher.
 */
@Plugin(
        id = "integritypolygon",
        name = "IntegrityPolygon",
        version = "2.0.0",
        description = "Modular server security management framework",
        authors = {"ErikRadovan"}
)
public class IntegrityPolygon {

    private final Logger logger;
    private final ProxyServer proxy;
    private final Path dataDirectory;

    private ConfigManager configManager;
    private ModuleManager moduleManager;
    private ModuleWatcher moduleWatcher;
    private WebServer webServer;
    private ExtenderSocketServer extenderSocketServer;
    private LogManager logManager;
    private MetricsCollector metricsCollector;

    @Inject
    public IntegrityPolygon(Logger logger, ProxyServer proxy, @DataDirectory Path dataDirectory) {
        this.logger = logger;
        this.proxy = proxy;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        // 1. Ensure directory structure
        Path modulesJarDir = dataDirectory.resolve("modules");
        Path moduleDataDir = dataDirectory.resolve("module-data");
        createDirectories(modulesJarDir, moduleDataDir);

        // 2. Initialize configuration
        configManager = new ConfigManager(dataDirectory);
        configManager.init();

        // 3. Create service registry and register core services
        ServiceRegistryImpl serviceRegistry = new ServiceRegistryImpl();
        serviceRegistry.register(ProxyServer.class, proxy);
        serviceRegistry.register(ConfigManager.class, configManager);

        // 4. Initialize logging
        logManager = new LogManager();
        serviceRegistry.register(LogManager.class, logManager);

        // 5. Initialize direct TCP socket for extender communication (primary, no players needed)
        int extenderPort = configManager.getExtenderPort();
        String extenderSecret = configManager.getExtenderSecret();
        extenderSocketServer = new ExtenderSocketServer(extenderPort, extenderSecret, logger);
        extenderSocketServer.start();

        // 5b. Initialize Extender service (bridges modules to Paper backend events)
        ExtenderServiceImpl extenderService = new ExtenderServiceImpl(
                extenderSocketServer, proxy, logger);
        serviceRegistry.register(ExtenderService.class, extenderService);

        // 6. Register shared services available to all modules
        HttpServiceImpl httpService = new HttpServiceImpl();
        serviceRegistry.register(HttpService.class, httpService);

        // 6b. Metrics service (Prometheus exposition)
        MetricsServiceImpl metricsService = new MetricsServiceImpl();
        serviceRegistry.register(MetricsService.class, metricsService);

        // 6c. In-memory metrics buffer for built-in monitoring charts
        MetricsBuffer metricsBuffer = new MetricsBuffer();

        // 7. Initialize module manager
        moduleManager = new ModuleManager(
                modulesJarDir, moduleDataDir, serviceRegistry,
                proxy.getEventManager(), this, logger
        );

        // 8. Generate initial admin credentials on first launch
        AuthManager authManager = new AuthManager(configManager);
        SetupWizard setupWizard = new SetupWizard(configManager, authManager);
        String generatedPassword = setupWizard.generateInitialCredentials();

        // 9. Start embedded web server (Javalin/Jetty logs are suppressed)
        webServer = new WebServer(configManager, moduleManager, logManager, proxy, logger, metricsService, metricsBuffer, dataDirectory);
        webServer.setExtenderService(extenderService);
        webServer.start();

        // 10. Register AlertService (depends on WebServer's RealtimeHandler)
        AlertServiceImpl alertService = new AlertServiceImpl(webServer.getRealtimeHandler());
        serviceRegistry.register(AlertService.class, alertService);

        // 10b. Start metrics collector (scrapes proxy + backend server stats)
        metricsCollector = new MetricsCollector(metricsService, metricsBuffer, proxy, extenderService, logger);
        metricsCollector.start();

        // 11. If setup is complete, load modules and optionally start hot-reload watcher
        if (configManager.isSetupCompleted()) {
            moduleManager.loadAll();

            if (configManager.isHotReloadEnabled()) {
                moduleWatcher = new ModuleWatcher(modulesJarDir, moduleManager, logger);
                Thread watcherThread = new Thread(moduleWatcher, "IP-ModuleWatcher");
                watcherThread.setDaemon(true);
                watcherThread.start();
            }
        }

        // 12. Print startup banner (after modules are loaded so we can show count)
        int port = configManager.getWebPort();
        int tcpPort = configManager.getExtenderPort();
        String bind = configManager.getWebBind();
        printStartupBanner(generatedPassword, bind, port, tcpPort);

        logManager.info("system", "startup", "IntegrityPolygon v2.0.0 initialized");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        logger.info("Shutting down IntegrityPolygon...");

        if (metricsCollector != null) {
            metricsCollector.stop();
        }
        if (moduleWatcher != null) {
            moduleWatcher.stop();
        }
        if (moduleManager != null) {
            moduleManager.shutdown();
        }
        if (webServer != null) {
            webServer.stop();
        }
        if (extenderSocketServer != null) {
            extenderSocketServer.stop();
        }

        logger.info("IntegrityPolygon shut down.");
    }

    private void printStartupBanner(String generatedPassword, String bind, int port, int tcpPort) {
        logger.info("");
        logger.info("  ╔══════════════════════════════════════════╗");
        logger.info("  ║   IntegrityPolygon  ·  v2.0.0           ║");
        logger.info("  ║   Modular Security Framework            ║");
        logger.info("  ╚══════════════════════════════════════════╝");
        logger.info("");
        logger.info("  ▸ Panel : https://{}:{}", bind.equals("0.0.0.0") ? "<your-ip>" : bind, port);
        logger.info("  ▸ Extender socket : port {}", tcpPort);

        // Show secret source — if no custom secret in config, we're using forwarding.secret
        boolean usingForwarding = configManager.<String>getValue("extender.secret")
                .map(String::isBlank).orElse(true);
        if (usingForwarding) {
            logger.info("  ▸ Extender auth : Velocity forwarding secret (auto)");
        } else {
            logger.info("  ▸ Extender auth : custom secret (see config.yml)");
        }

        if (generatedPassword != null) {
            logger.info("");
            logger.info("  ┌─────────────────────────────────────────┐");
            logger.info("  │  FIRST LAUNCH                           │");
            logger.info("  │  Username:  admin                       │");
            logger.info("  │  Password:  {}", String.format("%-28s│", generatedPassword));
            logger.info("  │                                         │");
            logger.info("  │  Log in and change your password,       │");
            logger.info("  │  then complete the setup wizard.        │");
            logger.info("  └─────────────────────────────────────────┘");
        } else if (!configManager.isSetupCompleted()) {
            logger.info("  ▸ Status: setup incomplete — open the panel to finish");
        } else {
            int moduleCount = 0;
            if (moduleManager != null) moduleCount = moduleManager.getLoadedModules().size();
            logger.info("  ▸ Status : ready");
            logger.info("  ▸ Modules: {} loaded", moduleCount);
        }

        logger.info("");
    }

    private void createDirectories(Path... dirs) {
        for (Path dir : dirs) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                logger.error("Failed to create directory: {}", dir, e);
            }
        }
    }
}
