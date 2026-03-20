# IntegrityPolygon — Module API Documentation

## Overview

IntegrityPolygon modules are hot-loaded JARs for the Velocity plugin.
Each module can:

- register Velocity event listeners
- register module dashboard API routes under `/api/modules/{id}/...`
- push realtime updates to `/ws/live`
- communicate with Paper servers through `ExtenderService`
- persist data and configuration in the shared SQLite database

The framework gives every module an isolated classloader and a scoped context.

---

## Quick Start

### 1. Maven setup

```xml
<dependencies>
    <dependency>
        <groupId>dev.erikradovan</groupId>
        <artifactId>integritypolygon</artifactId>
        <version>2.0.0-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>com.velocitypowered</groupId>
        <artifactId>velocity-api</artifactId>
        <version>3.4.0-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
    <dependency>
        <groupId>com.google.code.gson</groupId>
        <artifactId>gson</artifactId>
        <version>2.13.1</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### 2. Module descriptor (`src/main/resources/module.json`)

```json
{
  "id": "my-module",
  "name": "My Module",
  "version": "1.0.0",
  "description": "A short description of what this module does.",
  "author": "YourName",
  "main": "com.example.mymodule.MyModule",
  "dashboard": "web/"
}
```

### 3. Module entrypoint

> Use `dev.erikradovan.integritypolygon.api.Module` (fully-qualified) to avoid collision with `java.lang.Module`.

```java
package com.example.mymodule;

import dev.erikradovan.integritypolygon.api.ModuleContext;

public class MyModule implements dev.erikradovan.integritypolygon.api.Module {

    @Override
    public void onEnable(ModuleContext ctx) {
        ctx.getLogger().info("MyModule enabled");
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onReload() {
    }
}
```

---

## Core API

### ModuleContext

`ModuleContext` is module-scoped and passed to `onEnable`.

| Method | Description |
|---|---|
| `getDescriptor()` | Metadata from `module.json` |
| `getLogger()` | SLF4J logger scoped to this module |
| `getServiceRegistry()` | Shared cross-module service lookup |
| `getEventManager()` | Module-scoped event subscription manager |
| `getTaskScheduler()` | Module-scoped task scheduler |
| `getDashboard()` | Module dashboard route registration + pushes |
| `getDataDirectory()` | Module data directory path (optional direct file use) |
| `getStorage()` | SQLite-backed module data API (table creation + SQL execution) |
| `getConfigStore()` | Typed centralized module config API |

### ServiceRegistry

Framework services (example):

```java
var reg = ctx.getServiceRegistry();

var logs = reg.get(dev.erikradovan.integritypolygon.logging.LogManager.class).orElse(null);
var ext = reg.get(dev.erikradovan.integritypolygon.api.ExtenderService.class).orElse(null);
```

---

## SQLite Storage API

All module data is stored in the shared core SQLite database.

### ModuleStorage

`ModuleStorage` is module-scoped.

```java
var storage = ctx.getStorage();

String table = storage.qualifyTable("detections");
storage.ensureTable("detections", "(id INTEGER PRIMARY KEY AUTOINCREMENT, ip TEXT NOT NULL, created_at INTEGER NOT NULL)");

storage.update("INSERT INTO " + table + " (ip, created_at) VALUES (?, ?)", ip, System.currentTimeMillis());

storage.query("SELECT ip, created_at FROM " + table + " ORDER BY id DESC LIMIT ?", rs -> {
    while (rs.next()) {
        String foundIp = rs.getString("ip");
        long ts = rs.getLong("created_at");
    }
}, 20);
```

Rules:

- table names are auto-namespaced as `{moduleId}__{table}`
- module IDs and table names are validated to `[a-z0-9-]` and `[a-z0-9_]`
- use prepared parameters (`?`) for user input

---

## Centralized Config API

Each module registers typed options in the central SQLite config tables.

### Register schema + defaults

```java
var config = ctx.getConfigStore();

config.registerOptions(java.util.List.of(
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.bool("enabled", true, false, "Enable this module"),
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.integer("max_conn_per_ip", 3, false, "Per-IP join cap"),
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.string("kick_message", "Connection denied.", false, "Kick message"),
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.list("whitelisted_ips", java.util.List.of(), false, "Bypass list")
));

boolean enabled = config.getBoolean("enabled", true);
int cap = config.getInt("max_conn_per_ip", 3);
String msg = config.getString("kick_message", "Connection denied.");
java.util.List<String> ips = config.getStringList("whitelisted_ips");
```

### Update values

```java
config.set("enabled", true);
config.set("max_conn_per_ip", 5);
config.set("whitelisted_ips", java.util.List.of("127.0.0.1"));
```

Behavior:

- on register, schema rows are upserted (`type`, nullable, description, defaults)
- missing values are initialized from defaults
- values are stored centrally, not per-module YAML files

---

## Dashboard API

Register relative routes only:

```java
var dash = ctx.getDashboard();

dash.get("status", req -> req.json(java.util.Map.of("ok", true)));
dash.post("config", req -> {
    // /api/modules/{id}/config
    req.json(java.util.Map.of("success", true));
});
```

`context.getDashboard().get("status", ...)` maps to `/api/modules/{id}/status`.

Push realtime updates:

```java
dash.pushUpdate("stats_changed", java.util.Map.of("blocked", 42));
```

---

## Event Handling

```java
ctx.getEventManager().subscribe(new Object() {
    @com.velocitypowered.api.event.Subscribe
    public void onPreLogin(com.velocitypowered.api.event.connection.PreLoginEvent event) {
        // module logic
    }
});
```

---

## Frontend Notes

Dashboard files are loaded from `src/main/resources/web/` and served under `/modules/{id}/`.

- pass bearer token in `Authorization: Bearer <token>`
- call module routes under `/api/modules/{id}/...`
- keep UI unchanged when moving config to DB; only backend route implementation changes

---

## Lifecycle

```text
JAR copied into modules/
 -> ModuleWatcher detects change (2s debounce)
 -> ModuleManager parses module.json and resolves deps
 -> ModuleContext created (dashboard, scheduler, storage, config store)
 -> onEnable called
 -> onReload called on manual module reload
 -> onDisable called on unload/shutdown
```

---

## Build & Deploy

```cmd
mvn clean package -DskipTests
```

Copy resulting module JAR into the proxy plugin modules directory, then ModuleWatcher hot-loads it.

---

## Practical Guidance

1. Put user-configurable settings in `ModuleConfigStore`, not ad-hoc files.
2. Use `ModuleStorage` for structured/stateful data (history, registries, counters).
3. Keep dashboard routes relative (`"status"`, not `"/status"`).
4. Use `LogManager` for dashboard-visible logs.
5. Keep external API failures fail-open where security model allows.
