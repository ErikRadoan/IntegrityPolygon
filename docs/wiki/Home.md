# IntegrityPolygon Wiki

Welcome to the IntegrityPolygon wiki.

This wiki is focused on **module development** for the Velocity-side module system.

## Start Here

- [Module Creation Guide](Module-Creation-Guide)
- [Module Descriptor (module.json)](Module-Descriptor-(module.json))
- [Module Config and Storage (SQLite)](Module-Config-and-Storage-(SQLite))
- [Module Dashboard and API](Module-Dashboard-and-API)
- [Build, Test, and Deploy](Build,-Test,-and-Deploy)

## What IntegrityPolygon Modules Get

Every module runs with:

- Isolated classloader and module context
- Dashboard API routes under `/api/modules/{id}/...`
- Static dashboard files under `/modules/{id}/`
- Access to shared services through `ServiceRegistry`
- Module-scoped SQLite config and data access
- Optional Paper communication through `ExtenderService`

## Important Notes

- Implement `dev.erikradovan.integritypolygon.api.Module` (fully-qualified name) to avoid `java.lang.Module` conflicts.
- Module hot reload uses file watching with a 2-second stability debounce.
- Keep dashboard routes relative (example: `"status"`), not absolute.

