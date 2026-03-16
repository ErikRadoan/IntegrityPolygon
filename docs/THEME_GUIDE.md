# Dashboard Theme Guide

This guide defines the shared visual language for the main panel (`src/main/resources/panel/index.html`) and module dashboards (for example `modules/profiler/src/main/resources/web/index.html`).

## 1) Design goals
- Clean and operational: prioritize readability over decorative effects.
- Tight geometry: avoid oversized radii and pill-heavy UI.
- Consistent hierarchy: the same control type should look the same across pages.
- Theme-safe rendering: all colors come from CSS tokens, not hardcoded hex values in JS.

## 2) Core tokens
Use these tokens from `:root` and theme variants:
- Surfaces: `--bg-base`, `--bg-surface`, `--bg-card`, `--bg-input`
- Borders: `--border`, `--border-hl`
- Text: `--text`, `--text-dim`, `--text-muted`
- Semantic colors: `--accent`, `--blue`, `--green`, `--amber`, `--red`
- Shape: `--radius-sm` (controls), `--radius` (cards), `--radius-lg` (dialogs)

## 3) Components
- Cards: flat surface + 1px border, subtle hover (`border` shift and max `translateY(-1px)`).
- Buttons:
  - Primary: solid accent background (`--accent`) with matching border.
  - Secondary/default: surface background + border.
  - Danger/success: text tint only unless the action is critical.
- Tables: uppercase compact headers, low-contrast separators, monospaced numeric-heavy cells where useful.
- Charts: draw lines/grids from theme variables (no fixed white/green/blue constants).

## 4) Spacing and typography
- Standard card padding: `14-20px`.
- Compact controls: `6-10px` vertical padding.
- Use `Inter` for UI text, `JetBrains Mono` only for logs, method trees, and metrics-heavy data.

## 5) Motion
- Keep transitions short (`120-250ms`).
- Avoid bounce, spin, and dramatic scale effects in routine interactions.
- First-load animation is acceptable; repeated refresh animations should be minimal.

## 6) Module dashboard contract
- Module dashboards should support theme sync through `?theme=` and `ip-theme-change` postMessage.
- If a module draws custom graphs/canvas, consume token colors via `getComputedStyle(document.documentElement)`.
- Do not introduce module-specific visual systems that conflict with panel tokens.

