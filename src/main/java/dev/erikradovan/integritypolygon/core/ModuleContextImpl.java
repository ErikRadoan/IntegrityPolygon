package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.*;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * Concrete implementation of {@link ModuleContext} provided to each module.
 * Holds references to the shared service registry and module-specific resources.
 */
public class ModuleContextImpl implements ModuleContext {

    private final ServiceRegistry serviceRegistry;
    private final EventSubscriptionManager eventManager;
    private final TaskScheduler taskScheduler;
    private final ModuleDashboard dashboard;
    private final Logger logger;
    private final Path dataDirectory;
    private final ModuleDescriptor descriptor;

    public ModuleContextImpl(
            ServiceRegistry serviceRegistry,
            EventSubscriptionManager eventManager,
            TaskScheduler taskScheduler,
            ModuleDashboard dashboard,
            Logger logger,
            Path dataDirectory,
            ModuleDescriptor descriptor
    ) {
        this.serviceRegistry = serviceRegistry;
        this.eventManager = eventManager;
        this.taskScheduler = taskScheduler;
        this.dashboard = dashboard;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.descriptor = descriptor;
    }

    @Override
    public ServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    @Override
    public EventSubscriptionManager getEventManager() {
        return eventManager;
    }

    @Override
    public TaskScheduler getTaskScheduler() {
        return taskScheduler;
    }

    @Override
    public ModuleDashboard getDashboard() {
        return dashboard;
    }

    @Override
    public Logger getLogger() {
        return logger;
    }

    @Override
    public Path getDataDirectory() {
        return dataDirectory;
    }

    @Override
    public ModuleDescriptor getDescriptor() {
        return descriptor;
    }
}
