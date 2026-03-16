package dev.erikradovan.integritypolygon.core;

import dev.erikradovan.integritypolygon.api.Module;
import dev.erikradovan.integritypolygon.api.*;

/**
 * Container for a loaded module's runtime state.
 *
 * @param module      the module instance
 * @param descriptor  the module's metadata
 * @param context     the module's context
 * @param classLoader the isolated classloader for this module
 */
public record LoadedModule(
        Module module,
        ModuleDescriptor descriptor,
        ModuleContextImpl context,
        ModuleClassLoader classLoader
) {
}

