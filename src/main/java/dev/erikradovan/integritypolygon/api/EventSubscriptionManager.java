package dev.erikradovan.integritypolygon.api;

/**
 * Module-scoped event subscription manager. Wraps Velocity's EventManager
 * and automatically tracks all registrations so they can be bulk-unregistered
 * when the module is unloaded.
 *
 * <p>Modules register standard Velocity event listeners (classes with
 * {@code @Subscribe}-annotated methods). The framework registers them under the
 * main plugin instance so they receive all Velocity events and can cancel/modify them.
 */
public interface EventSubscriptionManager {

    /**
     * Register a listener object containing {@code @Subscribe}-annotated event handler methods.
     * The listener will receive Velocity events and can cancel/modify them.
     *
     * @param listener the listener instance
     */
    void subscribe(Object listener);

    /**
     * Unregister a specific listener.
     *
     * @param listener the listener instance to remove
     */
    void unsubscribe(Object listener);

    /**
     * Unregister all listeners that were registered through this manager.
     * Called automatically by the framework on module unload.
     */
    void unsubscribeAll();
}

