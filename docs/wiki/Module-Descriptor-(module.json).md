# Module Descriptor (module.json)

Each module must include `src/main/resources/module.json`.

## Example

```json
{
  "id": "my-module",
  "name": "My Module",
  "version": "1.0.0",
  "description": "Module description",
  "author": "YourName",
  "main": "dev.example.module.MyModule",
  "dashboard": "web/"
}
```

## Fields

- `id` (required): unique lowercase module id, used in API routes and filenames.
- `name` (required): display name.
- `version` (required): module version.
- `main` (required): fully-qualified Java class implementing module interface.
- `description` (optional): module description.
- `author` (optional): author string.
- `dashboard` (optional): static dashboard folder path inside jar (commonly `web/`).

## Rules

- Keep `id` stable across versions.
- Do not use spaces in `id`; use lowercase with hyphens.
- Ensure `main` points to an actual class in compiled output.

## Dependencies in Descriptor

If your module depends on another module, include `dependencies` in `module.json`.
Load order is resolved topologically by `ModuleManager`.

