package dev.erikradovan.integritypolygon.api;

import java.util.Optional;

/**
 * Service locator registry. The framework registers core services at startup
 * (ProxyServer, EventManager, ConfigManager, LogManager, etc.) and modules
 * look them up without needing interface changes for each new service.
 *
 * <p>Example usage in a module:
 * <pre>
 *   ProxyServer proxy = context.getServiceRegistry().get(ProxyServer.class).orElseThrow();
 * </pre>
 */
public interface ServiceRegistry {

    /**
     * Register a service implementation.
     *
     * @param type           the service interface class
     * @param implementation the implementation instance
     * @param <T>            service type
     */
    <T> void register(Class<T> type, T implementation);

    /**
     * Look up a registered service.
     *
     * @param type the service interface class
     * @param <T>  service type
     * @return the implementation, or empty if not registered
     */
    <T> Optional<T> get(Class<T> type);

    /**
     * Remove a previously registered service.
     *
     * @param type the service interface class
     * @param <T>  service type
     */
    <T> void unregister(Class<T> type);
}

