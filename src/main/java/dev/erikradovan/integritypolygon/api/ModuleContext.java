package dev.erikradovan.integritypolygon.api;

import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Context provided to each module on enable. Gives access to all framework
 * services without tight coupling — new services can be added to the
 * {@link ServiceRegistry} without changing this interface.
 */
public interface ModuleContext {

    /**
     * @return the shared service registry for looking up framework and inter-module services
     */
    ServiceRegistry getServiceRegistry();

    /**
     * @return the module-scoped event subscription manager for registering Velocity event listeners
     */
    EventSubscriptionManager getEventManager();

    /**
     * @return a module-scoped task scheduler for async and periodic work
     */
    TaskScheduler getTaskScheduler();

    /**
     * @return the module-scoped dashboard for registering custom UI and REST endpoints
     */
    ModuleDashboard getDashboard();

    /**
     * @return a logger instance prefixed with this module's ID
     */
    Logger getLogger();

    /**
     * @return the isolated data directory for this module (e.g., plugins/integritypolygon/module-data/{moduleId}/)
     */
    Path getDataDirectory();

    /**
     * @return this module's descriptor (metadata from module.json)
     */
    ModuleDescriptor getDescriptor();
}
