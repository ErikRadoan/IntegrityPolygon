package dev.erikradovan.integritypolygon.api;

import java.util.List;

/**
 * Immutable descriptor holding module metadata, parsed from module.json inside each module JAR.
 *
 * @param id           unique module identifier (e.g., "antibot")
 * @param name         human-readable name
 * @param version      semantic version string
 * @param mainClass    fully qualified main class implementing {@link Module}
 * @param authors      list of author names
 * @param dependencies list of module IDs this module depends on
 * @param description  short description of the module's purpose
 * @param dashboardPath path inside the JAR to the dashboard static files (e.g., "web/"),
 *                      or empty string if the module has no dashboard
 */
public record ModuleDescriptor(
        String id,
        String name,
        String version,
        String mainClass,
        List<String> authors,
        List<String> dependencies,
        String description,
        String dashboardPath
) {
    /**
     * @return true if this module ships a dashboard UI
     */
    public boolean hasDashboard() {
        return dashboardPath != null && !dashboardPath.isEmpty();
    }
}
