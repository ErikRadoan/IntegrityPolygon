﻿# IntegrityPolygon — Module API Documentation

## Overview

IntegrityPolygon is a modular server security framework for Velocity proxy servers.  
Modules are self-contained JAR files that are hot-loaded at runtime, each providing specific
security functionality (anti-bot, account protection, VPN blocking, geo-filtering, etc.).

Every module ships with its own **embedded dashboard** (HTML/CSS/JS) which is automatically
extracted and served as a sub-page in the main IntegrityPolygon web panel.

---

## Quick Start

### 1. Project Setup (Maven)

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

### 2. Module Descriptor (`src/main/resources/module.json`)

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

| Field         | Required | Description                                                      |
|---------------|----------|------------------------------------------------------------------|
| `id`          | ✅       | Unique identifier (lowercase, hyphenated). Used in API paths.    |
| `name`        | ✅       | Human-readable name shown in the dashboard.                      |
| `version`     | ✅       | Semantic version string.                                         |
| `description` | No       | Brief description shown in the module list.                      |
| `author`      | No       | Author name.                                                     |
| `main`        | ✅       | Fully-qualified class name implementing `Module`.                |
| `dashboard`   | No       | Path inside the JAR containing dashboard HTML files (e.g. `web/`)|

### 3. Implement the Module Interface

> **Important:** Since Java 9+ has `java.lang.Module`, you must use the fully-qualified
> name when implementing the interface.

```java
package com.example.mymodule;

import dev.erikradovan.integritypolygon.api.*;

public class MyModule implements dev.erikradovan.integritypolygon.api.Module {

    @Override
    public void onEnable(ModuleContext ctx) {
        // Called when the module is loaded
        ctx.getLogger().info("MyModule enabled!");
    }

    @Override
    public void onDisable() {
        // Called when the module is unloaded
    }

    @Override
    public void onReload() {
        // Called when the user triggers a config reload
    }
}
```

---

## Core API

### ModuleContext

The `ModuleContext` is passed to your module's `onEnable()` method. It provides access to
all framework services.

| Method                  | Returns            | Description                                    |
|-------------------------|--------------------|-------------------------------------------------|
| `getDescriptor()`      | `ModuleDescriptor` | Module metadata from module.json                |
| `getLogger()`          | `Logger`           | SLF4J logger scoped to this module              |
| `getProxyServer()`     | `ProxyServer`      | Velocity proxy server instance                  |
| `getServiceRegistry()` | `ServiceRegistry`  | Shared service registry                         |
| `getEventManager()`    | `EventManager`     | Subscribe to Velocity events                    |
| `getTaskScheduler()`   | `TaskScheduler`    | Schedule tasks (periodic, delayed)              |
| `getMessagingService()`| `MessagingService` | Send messages to backend servers                |
| `getDashboard()`       | `ModuleDashboard`  | Register REST endpoints and push dashboard data |

### ServiceRegistry

Access shared framework services:

```java
ServiceRegistry reg = ctx.getServiceRegistry();

ConfigManager config   = reg.get(ConfigManager.class).orElse(null);
LogManager logs        = reg.get(LogManager.class).orElse(null);
ExtenderService extend = reg.get(ExtenderService.class).orElse(null);
```

---

## Dashboard API

### Registering REST Endpoints

Each module gets its own namespaced API under `/api/modules/{module-id}/`:

```java
ModuleDashboard dashboard = ctx.getDashboard();

// GET /api/modules/my-module/status
dashboard.get("status", reqCtx -> {
    reqCtx.json(Map.of("online", true, "count", 42));
});

// POST /api/modules/my-module/config
dashboard.post("config", reqCtx -> {
    String body = reqCtx.body();
    reqCtx.json(Map.of("success", true));
});
```

### RequestContext Interface

```java
interface RequestContext {
    String body();                                // Request body as string
    String pathParam(String name);                // URL path parameter
    String queryParam(String name);               // Query parameter
    String queryParam(String name, String def);   // With default value
    String authenticatedUser();                   // JWT subject
    RequestContext status(int code);              // Set response status
    void json(Object obj);                        // Send JSON response
    void result(String text);                     // Send plain text
}
```

### Pushing Real-Time Updates

```java
dashboard.pushUpdate("stats_changed", Map.of(
    "blocked", totalBlocked.get()
));
```

---

## Configuration

```java
ConfigManager config = reg.get(ConfigManager.class).orElse(null);
String moduleId = ctx.getDescriptor().id;

// Read config
Map<String, Object> cfg = config.getModuleConfig(moduleId);

// Write config
cfg.put("enabled", true);
config.saveModuleConfig(moduleId, cfg);
```

Config is stored as YAML under `plugins/integritypolygon/modules/{id}.yml`.

---

## Logging

```java
LogManager logs = reg.get(LogManager.class).orElse(null);
logs.log("my-module", "INFO",  "STARTUP",  "Module initialized");
logs.log("my-module", "WARN",  "SECURITY", "Suspicious connection from 1.2.3.4");
logs.log("my-module", "ERROR", "API",      "External API call failed");
```

Logs appear in the dashboard Logs page with filtering by module, level, time range, and text search.

---

## Event Handling

```java
ctx.getEventManager().subscribe(new MyListener());

public class MyListener {
    @Subscribe
    public void onPreLogin(PreLoginEvent event) {
        String ip = event.getConnection().getRemoteAddress()
                         .getAddress().getHostAddress();
        // Block if needed
        event.setResult(PreLoginEvent.PreLoginComponentResult.denied(
            Component.text("Blocked!").color(NamedTextColor.RED)
        ));
    }
}
```

---

## Dashboard Frontend

Place your dashboard HTML in `src/main/resources/web/index.html`.
It's served as an iframe. The auth token is passed via query parameter:

```javascript
const token = new URLSearchParams(location.search).get('token');
const API = location.origin;

async function api(path, options = {}) {
    const headers = {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer ' + token
    };
    const r = await fetch(API + '/api/modules/my-module/' + path,
                          { ...options, headers });
    return r.json();
}

// Load status
const status = await api('status');

// Save config
await api('config', {
    method: 'POST',
    body: JSON.stringify({ enabled: true })
});
```

### Design Guidelines

- Dark theme: background `#07080f`, cards `#121425`, accent `#22d3a7`
- Font: Inter
- Use granular DOM updates (`textContent`) — never re-render forms on polling
- Poll stats every 5 seconds; load config once on init

---

## Module Lifecycle

```
JAR placed in modules/ directory
        │
  ModuleWatcher detects file → debounce (2s stability)
        │
  ModuleManager.loadModule()
        ├── Parse module.json
        ├── Create isolated ClassLoader
        ├── Instantiate main class
        ├── Create ModuleContext
        ├── Extract dashboard files
        └── Register static file handler
        │
  Module.onEnable(context) ← your code runs here
        │
  Module is RUNNING
        ├── Module.onReload() on config change
        │
  Module.onDisable() on unload/shutdown
        ├── Cleanup dashboard files
        ├── Close ClassLoader
        └── Unregister routes
```

---

## Existing Modules

| Module                  | Description                                                                     |
|-------------------------|---------------------------------------------------------------------------------|
| **Anti-Bot**            | Rate limiting, flood detection, bot heuristics, handshake validation            |
| **Account Protection**  | Staff 2FA (TOTP), session IP locking, enrollment management                    |
| **Identity Enforcement**| VPN/proxy detection via proxycheck.io with subnet-level caching                |
| **Geo-Filtering**       | Country-based blacklist/whitelist using geojs.io geolocation                   |
| **Server Monitor**      | Real-time backend performance profiling — TPS, chunk tick costs, plugin times, lag spike detection |

---


## Build & Deploy

```bash
mvn clean package -DskipTests
cp target/my-module-1.0.0.jar /path/to/velocity/plugins/IntegrityPolygon/modules/
# ModuleWatcher auto-detects and hot-loads the JAR
```

---

## Best Practices

1. **Fail-open on API errors** — Allow the player if an external API fails.
2. **Cache aggressively** — Cache by IP and /24 subnet with configurable TTL.
3. **Use the logging API** — Log through `LogManager` so events appear in the dashboard.
4. **Granular dashboard updates** — Only update changed DOM values on polling.
5. **Handle concurrency** — Use `ConcurrentHashMap`, `AtomicLong`, `volatile`.
6. **Avoid `Map.of()` with >10 entries** — Use `LinkedHashMap` for large maps (Java limit).
