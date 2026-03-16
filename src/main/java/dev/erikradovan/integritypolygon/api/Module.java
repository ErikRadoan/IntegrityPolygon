package dev.erikradovan.integritypolygon.api;

/**
 * Base interface that all IntegrityPolygon security modules must implement.
 * Modules are loaded as JAR files at runtime and managed by the framework.
 */
public interface Module {

    /**
     * Called when the module is loaded and enabled.
     * Use the provided context to access framework services, register event listeners, etc.
     *
     * @param context the module's runtime context
     */
    void onEnable(ModuleContext context);

    /**
     * Called when the module is being disabled/unloaded.
     * Clean up any resources here. Event listeners registered via the context
     * are automatically unregistered by the framework.
     */
    void onDisable();

    /**
     * Called when the module's configuration has been changed via the web panel.
     * Override to handle dynamic config reloads without a full module restart.
     * Default implementation does nothing.
     */
    default void onReload() {
        // no-op by default
    }
}

