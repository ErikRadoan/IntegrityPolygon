package ErikRadovan.integrityPolygon.ModuleLogic;

import ErikRadovan.integrityPolygon.API.ModuleContext;
import ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging.LoadSessionLogger;
import ErikRadovan.integrityPolygon.Services.ApiRegistry;
import ErikRadovan.integrityPolygon.Logging.LogCollector;
import ErikRadovan.integrityPolygon.Services.CrossProxyMessaging;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.util.Optional;

public class ModuleContextImpl implements ModuleContext {
    private final ProxyServer proxy;
    private final Logger logger;
    private final ApiRegistry registry;
    private final Object pluginInstance;
    private final LogCollector logCollector;
    private final File dataDirectory;
    private final CrossProxyMessaging crossProxyMessaging;
    private final LoadSessionLogger loggerTree;

    public ModuleContextImpl(ProxyServer proxy, Logger logger, ApiRegistry registry, Object pluginInstance, LogCollector logCollector, File dataDirectory, CrossProxyMessaging crossProxyMessaging, LoadSessionLogger loggerTree) {
        this.proxy = proxy;
        this.logger = logger;
        this.registry = registry;
        this.pluginInstance = pluginInstance;
        this.logCollector = logCollector;
        this.dataDirectory = dataDirectory;
        this.crossProxyMessaging = crossProxyMessaging;
        this.loggerTree = loggerTree;
    }

    @Override
    public <T> void registerApi(Class<T> apiClass, T implementation) {
        registry.registerApi(apiClass, implementation);
    }

    @Override
    public <T> void unregisterApi(Class<T> apiClass) {
        registry.unregisterApi(apiClass);
    }

    @Override
    public <T> Optional<T> getApi(Class<T> apiClass) {
        return registry.getApi(apiClass);
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public ProxyServer getProxy() {
        return proxy;
    }

    @Override
    public Object getPluginInstance() {
        return pluginInstance;
    }

    @Override
    public LogCollector getLogCollector() {
        return logCollector;
    }

    @Override
    public File getPluginPath() {return dataDirectory;}

    @Override
    public CrossProxyMessaging getCrossProxyMessaging() {
        return crossProxyMessaging;
    }

    @Override
    public LoadSessionLogger loggerTree() {return loggerTree;}


}

