package ErikRadovan.integrityPolygon;

import ErikRadovan.integrityPolygon.Panel.TokenGenerator;
import ErikRadovan.integrityPolygon.Services.ApiRegistry;
import ErikRadovan.integrityPolygon.CheckSumLogic.ChecksumDatabase;
import ErikRadovan.integrityPolygon.CheckSumLogic.RemoteChecksumDatabase;
import ErikRadovan.integrityPolygon.Config.Config;
import ErikRadovan.integrityPolygon.Logging.LogCollector;
import ErikRadovan.integrityPolygon.ModuleLogic.ModuleLoader;
import ErikRadovan.integrityPolygon.ModuleLogic.ModuleWatcher;
import ErikRadovan.integrityPolygon.Panel.CommunicationModule;
import ErikRadovan.integrityPolygon.Services.CrossProxyMessaging;
import com.google.inject.Inject;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import io.ably.lib.types.AblyException;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;


@Plugin(id = "integritypolygon", name = "IntegrityPolygon", version = "1.0")
public class IntegrityPolygon {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxy;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private final ApiRegistry apiRegistry = new ApiRegistry();
    private final Set<Object> loadedModules = new HashSet<>();

    CommunicationModule communicationModule;
    CrossProxyMessaging crossProxyMessaging;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {

        File moduleDir = new File(dataDirectory + "/modules");
        File configDir = new File(dataDirectory + "/config/");
        if (!configDir.exists()) configDir.mkdirs();
        if (!moduleDir.exists()) moduleDir.mkdirs();

        Config.init(dataDirectory.toFile());

        ChecksumDatabase checksumDb;
        try { //TODO: Add error handling
            checksumDb = new RemoteChecksumDatabase("https://raw.githubusercontent.com/ErikRadoan/IntegrityPolygon-Data/main/checksums.json");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        try {
            communicationModule = new CommunicationModule("nJbg5w.I5E-uw:Jd_XerRmBakhVASKQCXk37MIKQwQlC9cXwW0OiOMumM");
        } catch (AblyException e) {
            throw new RuntimeException(e);
        }

        printHeader();

        // Initiate the log collector
        LogCollector logCollector = new LogCollector(communicationModule);

        //CrossProxyMessaging crossProxyMessaging = new CrossProxyMessaging(proxy);

        // Starts the module loading process
        ModuleLoader moduleLoader = new ModuleLoader(moduleDir, checksumDb, apiRegistry, logger, proxy, this, logCollector, configDir, crossProxyMessaging);
        moduleLoader.loadModules();

        // Starts the module watching service
        if((boolean) Config.getValue(Config.Key.MODULE_WATCH).orElse(true)) {
            ModuleWatcher watcher = new ModuleWatcher(moduleDir, moduleLoader, logger);
            new Thread(watcher).start();
        }

    }



    private void printHeader() {
        String green = "\u001B[38;5;82m";
        String gray = "\u001B[38;5;240m";
        String reset = "\u001B[0m";

        System.out.println(
                gray +
                        " _ ___    \n" + gray +
                        "(_)  _ \\ \n" + gray +
                        "| | |_) )" + green + "   IntegrityPolygon\n" + gray +
                        "| |  __/ " + gray +  "       " + getClass().getPackage().getImplementationVersion() + "\n" + gray +
                        "| | |    \n" + gray +
                        "(_)_)    \n" + gray +
                        reset
        );
    }

}
