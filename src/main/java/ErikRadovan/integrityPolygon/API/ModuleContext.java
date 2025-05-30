package ErikRadovan.integrityPolygon.API;
import ErikRadovan.integrityPolygon.Logging.LogCollector;
import ErikRadovan.integrityPolygon.ModuleLogic.LoadLogging.LoadSessionLogger;
import ErikRadovan.integrityPolygon.Services.CrossProxyMessaging;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.util.Optional;

public interface ModuleContext {
    <T> void registerApi(Class<T> apiClass, T implementation);
    <T> void unregisterApi(Class<T> apiClass);
    <T> Optional<T> getApi(Class<T> apiClass);
    Logger getLogger();
    ProxyServer getProxy();
    Object getPluginInstance();
    LogCollector getLogCollector();
    File getPluginPath();
    CrossProxyMessaging getCrossProxyMessaging();
    LoadSessionLogger loggerTree();

}

