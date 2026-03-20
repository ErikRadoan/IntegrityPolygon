# AGENTS.md

## Big picture
- `src/main/java/dev/erikradovan/integritypolygon/IntegrityPolygon.java` is the real bootstrap: it creates `modules/`, `module-data/`, config, shared services, the TCP extender socket, Javalin, then `ModuleManager`.
- This codebase is really 3 systems: the Velocity plugin in `src/main`, the Paper bridge in `extender/`, and hot-loaded Velocity modules in `modules/*`. `modules/profiler/profiler-extender/` is a Paper-side module deployed through the extender.
- Browser traffic goes to Javalin (`/api/*`, `/ws/live`); module dashboards are extracted from each module JAR's `web/` folder and served under `/modules/{id}/` by `src/main/java/.../core/ModuleDashboardImpl.java`.
- Paper servers do **not** use plugin messaging: `src/main/java/.../messaging/ExtenderSocketServer.java` and `extender/src/main/java/.../ExtenderPlugin.java` talk over a length-prefixed TCP JSON tunnel on port `3491`.
- Module loading is descriptor-driven: `ModuleManager` reads `module.json`, topologically sorts dependencies, gives each module an isolated classloader/context, and hot-reloads changed JARs via `ModuleWatcher` after a 2s stability delay.

## Conventions that matter here
- When implementing modules, use the fully qualified interface name `dev.erikradovan.integritypolygon.api.Module`; `docs/MODULE_API.md` calls this out because Java has `java.lang.Module`.
- A normal module layout is Java entrypoint + `src/main/resources/module.json` + optional `src/main/resources/web/index.html`. See `modules/anti-bot` and `modules/geo-filtering`.
- Dashboard route registration is relative, not absolute: `context.getDashboard().get("status", ...)` becomes `/api/modules/{id}/status`.
- Module dashboards should use the framework wrapper (`ModuleDashboard.RequestContext`) instead of depending on Javalin types directly.
- Module config is expected to round-trip through `ConfigManager`; common pattern is “load config, write defaults if empty, then reload”, as seen in `AntiBotModule`, `GeoFilteringModule`, and `SpikeDetectorModule`.
- Core/plugin dependencies are usually `provided` in module POMs (`integritypolygon`, Velocity/Paper APIs, Gson, SLF4J). Shade only private libraries; example: `modules/account-protection/pom.xml` relocates `com.eatthepath`.

## Build, run, and debug workflow
- Use Java 21. The root plugin, extender, and modules all compile with Maven `release 21`; Docker images in `test-env/docker-compose.yml` are also Java 21.
- Build in dependency order: root plugin first (`install` so module builds can resolve `dev.erikradovan:integritypolygon`), then `extender`, then `modules/profiler/profiler-extender`, then individual modules.
```cmd
mvn clean package install -DskipTests
cd /d extender && mvn clean package -DskipTests
cd /d modules\profiler\profiler-extender && mvn clean package -DskipTests
cd /d modules\anti-bot && mvn clean package -DskipTests
```
- `build-all.sh` mirrors the same dependency order and currently builds `anti-bot`, `account-protection`, `identity-enforcement`, `geo-filtering`, `profiler`, and `spike-detector`.
- `build-all.sh` resolves Maven in this order: `MVN_BIN` env var, `mvn` on `PATH`, then IntelliJ bundled Maven (`.../IntelliJ IDEA 2025.3.1/.../mvn.cmd`).
- To refresh `repo/modules.json`, `repo/checksums.json`, and `repo/modules/*.jar` from built module artifacts, run `scripts/generate-modules-repo.cmd` (wrapper over `scripts/generate-modules-repo.ps1`).
- `test-env/deploy.ps1` is the real integration workflow: it strips UTF-8 BOMs, uses IntelliJ's bundled `mvn.cmd`, deploys the built JARs into `test-env/velocity/plugins`, `test-env/paper/plugins`, and `velocity/plugins/integritypolygon/modules`.
- First-run Docker flow is PowerShell, not Maven-only: `./test-env/deploy.ps1 -Build -Start`, wait for startup, then `./test-env/deploy.ps1 -SyncSecret` so Paper gets the proxy's extender secret.
- Expected local endpoints from `test-env/docker-compose.yml`: Minecraft proxy `localhost:25577`, web panel `http://localhost:3490`, extender socket `3491`.
- There are currently no `src/test` trees; practical validation is compile + Docker/manual verification + hitting the module dashboard/API routes you changed.

## Integration points and cross-component patterns
- `ExtenderServiceImpl` is the abstraction modules use for Paper communication; messages are namespaced by `module` + `type` JSON envelope fields.
- Extender auth now prefers Velocity's `forwarding.secret` (or `VELOCITY_FORWARDING_SECRET`) via `ConfigManager#getExtenderSecret`; `extender.secret` is only used/generated as fallback.
- Examples of cross-component patterns: `spike-detector` subscribes to `profiling_report`, `account-protection` sends `show_2fa_prompt`, and `profiler` deploys its bundled `profiler-extender.jar` during `onEnable()`.
- External HTTP dependencies live in modules, not in core: `geo-filtering` calls `geojs.io`, `identity-enforcement` calls `proxycheck.io`, and repository metadata is pulled by `RepositoryRoutes`.
- Remote repository/checksum defaults live in `src/main/resources/config.yml`; if remote fetch fails, `RepositoryRoutes` falls back to `plugins/integritypolygon/repo/modules.json`.

## Repository-specific gotchas
- The current module directories are `anti-bot`, `account-protection`, `identity-enforcement`, `geo-filtering`, `profiler`, and `spike-detector`; `test-env/deploy.ps1` and `build-all.sh` target this fixed set, while `scripts/generate-modules-repo.ps1` syncs `repo/` from whichever module artifacts are currently built.
- The root plugin shades/relocates Javalin, Jetty, and Kotlin; avoid adding module code that relies on those shaded package names directly.
- If you touch hot-reload behavior, preserve the existing “register routes once, swap delegates on reload” pattern in `ModuleDashboardImpl` and the 2s debounce in `ModuleWatcher`.

