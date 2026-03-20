# Module Dashboard and API

Module dashboard APIs are registered through `ModuleDashboard`.

## Route Mapping

Register with relative route names:

```java
ctx.getDashboard().get("status", this::handleStatus);
ctx.getDashboard().post("config", this::handleConfigSave);
```

Mapped URLs:

- `/api/modules/{module-id}/status`
- `/api/modules/{module-id}/config`

## Request Context

Use `ModuleDashboard.RequestContext` methods:

- `body()`
- `pathParam(name)`
- `queryParam(name)`
- `status(code)`
- `json(obj)`
- `result(text)`
- `authenticatedUser()`

## Realtime Push

```java
ctx.getDashboard().pushUpdate("stats_changed", java.util.Map.of("blocked", 42));
```

Events are broadcast through the panel websocket (`/ws/live`).

## Frontend Integration

Dashboard static files come from `src/main/resources/web/` and are served under `/modules/{id}/`.

Frontend calls should use:

- `Authorization: Bearer <token>` header
- `/api/modules/{id}/...` endpoints

## Stability Rules

- Keep route names stable to avoid frontend breakage.
- Keep response JSON fields backward-compatible where possible.
- Use module logger/log manager for user-visible operations and failures.

