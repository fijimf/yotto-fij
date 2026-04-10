# Light Theme Migration Design

**Date:** 2026-04-10
**Status:** Approved

## Summary

Convert the public-facing Yotto FIJ site from its current dark theme to a light theme: light grey body with a dark navy navigation bar and a bright blue accent. The admin panel (`/admin`) retains the existing dark theme unchanged. D3.js chart re-theming is out of scope and deferred.

---

## Direction

**Option C — Light Grey Body + Dark Navy Nav**

- Page background: light grey (`#f4f6f8`)
- Cards / surfaces: white (`#ffffff`)
- Navigation bar: dark navy (`#1e3a5f`) — unchanged feel at the top
- Accent / primary: bright blue (`#3b82f6`)
- Admin: stays dark, no changes

---

## Architecture

**Approach: Single-file token override**

All color tokens live in `main.css`. The `:root` block is updated to light values. A new `[data-theme="dark"]` selector block in the same file restores the dark tokens for the admin panel.

The admin layout template (`templates/layout/default.html` for admin, or the admin-specific layout if one exists) gets `data-theme="dark"` added to its `<html>` element. No new CSS files are created.

---

## Token Changes

### `:root` (light — new default)

| Token | Old (dark) | New (light) |
|-------|-----------|-------------|
| `--color-bg` | `#1a1a2e` | `#f4f6f8` |
| `--color-bg-surface` | `#16213e` | `#ffffff` |
| `--color-bg-surface-hover` | `#1f3056` | `#eef3f8` |
| `--color-primary` | `#9393a3` | `#3b82f6` |
| `--color-primary-hover` | `#b3b3d3` | `#2563eb` |
| `--color-text` | `#e0e0e0` | `#1e293b` |
| `--color-text-muted` | `#a0a0b0` | `#64748b` |
| `--color-text-inverse` | `#1a1a2e` | `#ffffff` |
| `--color-border` | `#2a2a4a` | `#dce3ea` |
| `--color-success` | `#2ecc71` | `#16a34a` |
| `--color-danger` | `#e74c3c` | `#dc2626` |
| `--shadow` | `0 2px 8px rgba(0,0,0,0.3)` | `0 2px 8px rgba(0,0,0,0.08)` |

### New nav-specific tokens (added to `:root`)

The nav bar stays dark regardless of theme. Rather than fighting `--color-bg-surface` (which becomes white), the nav components switch to dedicated tokens.

| Token | Value |
|-------|-------|
| `--color-nav-bg` | `#1e3a5f` |
| `--color-nav-text` | `#93b4d8` |
| `--color-nav-text-active` | `#ffffff` |
| `--color-nav-border` | `#2d5188` |

These tokens are defined in both `:root` (light) and `[data-theme="dark"]` (dark):

| Token | Light value | Dark value |
|-------|------------|------------|
| `--color-nav-bg` | `#1e3a5f` | `#16213e` (current `--color-bg-surface`) |
| `--color-nav-text` | `#93b4d8` | `#a0a0b0` (current `--color-text-muted`) |
| `--color-nav-text-active` | `#ffffff` | `#9393a3` (current `--color-primary`) |
| `--color-nav-border` | `#2d5188` | `#2a2a4a` (current `--color-border`) |

### `[data-theme="dark"]` (admin override — added to `main.css`)

All tokens above are redefined back to their current dark values inside this selector, so admin pages are visually unchanged.

---

## Component Changes

### Nav (`site-header`, `nav__*`)

- `.site-header`: `background` → `var(--color-nav-bg)`, `border-bottom` → `var(--color-nav-border)`
- `.quote-banner`: `background` → `var(--color-nav-bg)`, border uses `var(--color-nav-border)`
- `.nav__brand`: `color` → `var(--color-nav-text-active)`
- `.nav__link`: `color` → `var(--color-nav-text)`
- `.nav__link:hover`: `color` → `var(--color-nav-text-active)`, `background` → `rgba(255,255,255,0.1)`
- `.nav__link--active`: `color` → `var(--color-nav-text-active)`
- `.nav__toggle`: `color` → `var(--color-nav-text-active)`

### Alerts (hardcoded rgba → token-based)

Current alert backgrounds use hardcoded rgba with dark-theme-tuned opacity (0.15). These get updated to use lighter opacity (0.08) that reads well on a white surface:

- `.alert--danger`: `background: rgba(220, 38, 38, 0.08)`
- `.alert--success`: `background: rgba(22, 163, 74, 0.08)`
- `.alert--warning`: `background: rgba(217, 119, 6, 0.08)`, border/color → `#d97706`

### Status badges (hardcoded rgba → lighter)

- `.status-badge--running`: `background: rgba(59, 130, 246, 0.12)`, `color: #2563eb`
- `.status-badge--completed`: `background: rgba(22, 163, 74, 0.12)`
- `.status-badge--failed`: `background: rgba(220, 38, 38, 0.12)`
- `.status-badge--partial`: `background: rgba(217, 119, 6, 0.12)`, `color: #d97706`

### Shadow

`--shadow` opacity reduced from `0.3` to `0.08` — dark shadows look heavy on a white surface.

### Button hover colours

- `.btn--danger:hover`: `#b91c1c` (darker red, better contrast on white)
- `.btn--success:hover`: `#15803d` (darker green, better contrast on white)

---

## Template Changes

All admin pages share the single `templates/layout/default.html` layout (confirmed — every template under `templates/admin/` uses `layout:decorate="~{layout/default}"`). There is no separate admin layout.

One change only, in `default.html`: add a Thymeleaf `th:attr` to the `<html>` element that sets `data-theme="dark"` when the request path starts with `/admin`:

```html
<html lang="en"
      th:attr="data-theme=${#request.requestURI.startsWith('/admin') ? 'dark' : null}"
      xmlns:th="http://www.thymeleaf.org"
      ...>
```

`#request` is the standard Thymeleaf expression object for `HttpServletRequest`, available in all Spring MVC templates. When `data-theme` is `null`, Thymeleaf omits the attribute entirely, so public pages have no `data-theme` attribute and pick up the `:root` light tokens.

---

## Out of Scope

- D3.js chart re-theming (game detail page) — deferred
- Dark/light mode toggle — not planned
- Admin panel restyling — stays dark

---

## Files Changed

| File | Change |
|------|--------|
| `src/main/resources/static/css/main.css` | Update `:root` tokens, add `[data-theme="dark"]` block, add nav tokens, update nav/alert/badge/button component styles |
| `src/main/resources/templates/layout/default.html` | Add `th:attr` to `<html>` to set `data-theme="dark"` for `/admin` paths |
| `UI.md` | Update color palette table to reflect new light theme tokens |
