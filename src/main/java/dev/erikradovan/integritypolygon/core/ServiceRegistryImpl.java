package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.ServiceRegistry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe service registry backed by a ConcurrentHashMap.
 * The framework pre-registers core services on startup; modules look them up at will.
 */
public class ServiceRegistryImpl implements ServiceRegistry {

    private final Map<Class<?>, Object> services = new ConcurrentHashMap<>();

    @Override
    public <T> void register(Class<T> type, T implementation) {
        services.put(type, implementation);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> get(Class<T> type) {
        Object impl = services.get(type);
        if (impl != null && type.isInstance(impl)) {
            return Optional.of(type.cast(impl));
        }
        return Optional.empty();
    }

    @Override
    public <T> void unregister(Class<T> type) {
        services.remove(type);
    }
}

