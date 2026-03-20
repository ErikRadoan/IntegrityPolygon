# Module Config and Storage (SQLite)

IntegrityPolygon uses a shared SQLite database for module config and data.

## Config: `ModuleConfigStore`

Each module gets a scoped config store from `ModuleContext`.

### Typical Pattern

1. Register options schema on enable.
2. Read typed values.
3. Update values from dashboard API handlers.

```java
var config = ctx.getConfigStore();
config.registerOptions(java.util.List.of(
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.bool("enabled", true, false, "Enable feature"),
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.string("kick_message", "Denied", false, "Kick message"),
    dev.erikradovan.integritypolygon.api.ModuleConfigOption.list("whitelist", java.util.List.of(), false, "Allowed IPs")
));
```

### Typed Reads

- `getBoolean(key, defaultValue)`
- `getInt(key, defaultValue)`
- `getDouble(key, defaultValue)`
- `getString(key, defaultValue)`
- `getStringList(key)`

### Writes

- `set(key, value)`
- `setAll(map)`

## Data: `ModuleStorage`

Use `ModuleStorage` for structured runtime data.

### Create Table

```java
var storage = ctx.getStorage();
storage.ensureTable("known_players", "(player_name TEXT PRIMARY KEY)");
```

### Insert/Update

```java
String table = storage.qualifyTable("known_players");
storage.update("INSERT INTO " + table + " (player_name) VALUES (?)", playerName);
```

### Query

```java
storage.query("SELECT player_name FROM " + table, rs -> {
    while (rs.next()) {
        String name = rs.getString("player_name");
    }
});
```

## Table Namespacing

`qualifyTable("events")` creates a module-safe physical table name based on module id.

## Best Practices

- Register config schema on every enable (idempotent).
- Use typed config getters and avoid ad-hoc parsing.
- Keep SQL parameterized for dynamic values.
- Keep dashboard contract unchanged while changing persistence backend.

