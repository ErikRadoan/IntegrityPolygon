package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.EventSubscriptionManager;
import com.velocitypowered.api.event.EventManager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Module-scoped event subscription manager. Each module gets its own instance.
 * Listeners are registered under the main plugin instance so they receive
 * standard Velocity events and can cancel/modify them via {@code @Subscribe}.
 *
 * <p>On module unload, {@link #unsubscribeAll()} is called automatically to
 * remove all listeners this module registered.
 */
public class EventSubscriptionManagerImpl implements EventSubscriptionManager {

    private final EventManager velocityEventManager;
    private final Object pluginInstance;
    private final Set<Object> trackedListeners = ConcurrentHashMap.newKeySet();

    public EventSubscriptionManagerImpl(EventManager velocityEventManager, Object pluginInstance) {
        this.velocityEventManager = velocityEventManager;
        this.pluginInstance = pluginInstance;
    }

    @Override
    public void subscribe(Object listener) {
        velocityEventManager.register(pluginInstance, listener);
        trackedListeners.add(listener);
    }

    @Override
    public void unsubscribe(Object listener) {
        velocityEventManager.unregisterListener(pluginInstance, listener);
        trackedListeners.remove(listener);
    }

    @Override
    public void unsubscribeAll() {
        for (Object listener : trackedListeners) {
            velocityEventManager.unregisterListener(pluginInstance, listener);
        }
        trackedListeners.clear();
    }
}

