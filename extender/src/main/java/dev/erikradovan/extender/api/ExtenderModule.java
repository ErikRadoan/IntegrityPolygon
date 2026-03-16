package dev.erikradovan.extender.api;

/**
 * Interface for dynamically loaded extender modules.
 *
 * <p>Extender modules run on Paper backend servers inside the IntegrityPolygon
 * Extender plugin. They are deployed from the Velocity proxy over the TCP
 * tunnel and loaded at runtime.
 *
 * <p>Each extender module JAR must contain an {@code extender-module.json}:
 * <pre>{@code
 * {
 *   "id": "profiler",
 *   "main": "dev.erikradovan.profiler.extender.ProfilerExtenderModule",
 *   "version": "1.0.0"
 * }
 * }</pre>
 */
public interface ExtenderModule {

    /**
     * Called when this extender module is loaded.
     *
     * @param context provides access to the Paper plugin, messaging, and data storage
     */
    void onEnable(ExtenderModuleContext context);

    /**
     * Called when this extender module is unloaded.
     */
    void onDisable();
}

