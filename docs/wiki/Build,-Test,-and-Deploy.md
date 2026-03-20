# Build, Test, and Deploy

## Build Order

Build in dependency order so modules resolve the core artifact:

```cmd
cd /d C:\Users\erikr\Projects\IdeaProjects\IntegrityPolygon
mvn clean package install -DskipTests

cd /d extender
mvn clean package -DskipTests

cd /d ..\modules\profiler\profiler-extender
mvn clean package -DskipTests

cd /d ..\..\anti-bot
mvn clean package -DskipTests
```

Or use the root build helper:

```cmd
cd /d C:\Users\erikr\Projects\IdeaProjects\IntegrityPolygon
bash build-all.sh
```

## Local Integration Flow

Use the test environment deploy workflow:

```powershell
./test-env/deploy.ps1 -Build -Start
./test-env/deploy.ps1 -SyncSecret
```

Expected local endpoints:

- Minecraft proxy: `localhost:25577`
- Web panel: `http://localhost:3490`
- Extender socket: `3491`

## Module Validation Checklist

- Module loads cleanly on startup.
- Dashboard page loads under `/modules/{id}/`.
- Module API endpoints respond under `/api/modules/{id}/...`.
- Config changes persist across restart.
- Any runtime data persists correctly in SQLite tables.

## Common Issues

- Missing core artifact in local Maven repo: run root `install` first.
- Module not reloading: confirm jar replacement completed and watcher debounce elapsed.
- Extender communication issues: verify synced secret and running Paper extender plugin.

