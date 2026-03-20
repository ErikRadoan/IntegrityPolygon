# Module Creation Guide

This page is the practical end-to-end guide for creating a new IntegrityPolygon module.

## 1) Create Module Project

Create a folder in `modules/`:

- `modules/your-module/`

Recommended structure:

- `src/main/java/.../YourModule.java`
- `src/main/resources/module.json`
- `src/main/resources/web/index.html` (optional dashboard)
- `pom.xml`

## 2) Add Maven Dependencies

Use `provided` scope for framework and platform APIs:

- `dev.erikradovan:integritypolygon`
- `com.velocitypowered:velocity-api`
- `com.google.code.gson:gson`
- `org.slf4j:slf4j-api`

Only shade private third-party libraries required by your module.

## 3) Implement Module Entrypoint

```java
package dev.example.module;

import dev.erikradovan.integritypolygon.api.ModuleContext;

public class YourModule implements dev.erikradovan.integritypolygon.api.Module {
    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.getLogger().info("YourModule enabled");
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onReload() {
    }
}
```

## 4) Register Config Schema (SQLite)

Use module-scoped centralized config via `ctx.getConfigStore()`:

```java
var config = ctx.getConfigStore();
config.registerOptions(java.util.List.of(
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.bool("enabled", true, false, "Enable module"),
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.integer("interval_sec", 10, false, "Polling interval")
));

boolean enabled = config.getBoolean("enabled", true);
int interval = config.getInt("interval_sec", 10);
```

## 5) Persist Runtime Data (SQLite)

Use module-scoped storage via `ctx.getStorage()`:

```java
var storage = ctx.getStorage();
storage.ensureTable("events", "(id INTEGER PRIMARY KEY AUTOINCREMENT, created_at INTEGER NOT NULL, message TEXT NOT NULL)");

String table = storage.qualifyTable("events");
storage.update("INSERT INTO " + table + " (created_at, message) VALUES (?, ?)", System.currentTimeMillis(), "Started");
```

## 6) Add Dashboard Endpoints

Register routes using relative paths:

```java
var d = ctx.getDashboard();
d.get("status", req -> req.json(java.util.Map.of("ok", true)));
d.post("config", req -> req.json(java.util.Map.of("success", true)));
```

This maps to:

- `GET /api/modules/{id}/status`
- `POST /api/modules/{id}/config`

## 7) Build and Install

Build order matters:

```cmd
cd /d C:\path\to\IntegrityPolygon
mvn clean package install -DskipTests

cd /d modules\your-module
mvn clean package -DskipTests
```

Drop resulting module jar into the IntegrityPolygon modules directory in Velocity.

## 8) Validate

- Open web panel and verify module appears.
- Hit module routes under `/api/modules/{id}/...`.
- Confirm config reads/writes persist after restart.
- Confirm hot-reload behavior by replacing module jar.

