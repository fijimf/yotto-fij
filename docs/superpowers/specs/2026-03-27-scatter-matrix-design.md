# Scatter Plot Matrix — Design Spec
_Date: 2026-03-27_

## Overview

A scatter plot matrix (SPLOM) tab on the existing `/rankings/comprehensive` page. Plots every numeric column from the Comprehensive Rankings table against every other, allowing visual inspection of correlations across all power-ranking variables for a given season and date.

---

## Variables

8 numeric columns from `ComprehensiveRankingRow` (W and L excluded — they are components of W% and are near-perfectly correlated with each other, adding density without insight):

| Short label | Field | Format |
|---|---|---|
| W% | `winPct` | 3 decimal places |
| PPG | `meanPtsFor` | 1 decimal place |
| OPP | `meanPtsAgainst` | 1 decimal place |
| ± | `meanMargin` | 1 decimal place, signed |
| RPI | `rpi` | 4 decimal places |
| Massey | `masseyRating` | 2 decimal places, signed |
| B-T | `bradleyTerryRating` | 3 decimal places |
| BTW | `bradleyTerryWeightedRating` | 3 decimal places |

This yields an 8×8 matrix: 28 unique scatter plot pairs (lower triangle), 8 diagonal cells, 28 correlation r cells (upper triangle).

---

## Architecture

### Data Flow

1. User visits `/rankings/comprehensive` — existing page loads with **Table** tab active (unchanged behavior).
2. User clicks **Scatter Matrix** tab for the first time → HTMX fires `GET /rankings/comprehensive/{year}/scatter-matrix?date=`.
3. Fragment response contains:
   - A `<script id="scatter-data" type="application/json">` block with team data as a pre-rendered JSON string (controller serializes via Jackson `ObjectMapper`, passes as `String scatterDataJson` to model, rendered with `th:utext`).
   - A `<div id="scatter-matrix-root">` where D3 mounts.
   - A `<script>` that bootstraps D3 rendering once the fragment is in the DOM.
4. D3 reads the JSON, computes Pearson r for all 28 variable pairs, then renders the 8×8 grid lazily via `IntersectionObserver`.
5. Subsequent tab switches are pure CSS show/hide — no re-fetch.
6. Date picker or season selector change: HTMX swaps the entire tab content area, clearing both tabs and reverting to Table active.

### Data Serialization

Each team serializes to a flat JS object:

```json
{ "id": 42, "name": "Duke Blue Devils", "conf": "ACC",
  "winPct": 0.848, "ppg": 82.1, "opp": 65.3, "margin": 16.8,
  "rpi": 0.6421, "massey": 24.3, "bt": 1.842, "btw": 2.104 }
```

Null values serialize as `null`. D3 silently skips null-valued points in scatter plots — they are excluded from both rendering and Pearson r computation with no visual indicator.

---

## Routes

| Method | Path | Description |
|---|---|---|
| `GET` | `/rankings/comprehensive/{year}/scatter-matrix` | HTMX fragment. Param: `?date=` (optional, defaults to latest). |

The existing `/rankings/comprehensive` and `/rankings/comprehensive/{year}/table` routes are unchanged.

---

## Controller Changes

`ComprehensiveRankingsController` gains one new handler method:

```java
@GetMapping("/rankings/comprehensive/{year}/scatter-matrix")
public String scatterMatrix(@PathVariable Integer year,
                            @RequestParam(required = false) LocalDate date,
                            Model model)
```

Internally: calls the existing `buildRows()` method, maps each `ComprehensiveRankingRow` to the flat JS object structure above (8 numeric fields + id, name, conf), serializes the list to JSON via `ObjectMapper`, and adds `scatterDataJson` and `hasData` to the model. Returns `"fragments/scatter-matrix :: scatter-matrix"`.

No new services, repositories, or entities required.

---

## Tab UI

The tab strip is inserted between the date picker row and the content container on `comprehensive-rankings.html`. It replaces the current `<div id="comp-rankings-container">` as the outermost wrapper:

```
[As of: date picker]   Latest: 2026-03-14

[ Table ]  [ Scatter Matrix ]
──────────────────────────────────────────────
  {active tab content — HTMX-swappable}
```

### Behavior

- **Table tab (default active):** shows `#comp-rankings-container` (existing). Already loaded on page render.
- **Scatter Matrix tab:** On first click, JS checks whether `#scatter-matrix-root` has child nodes; if not, fires HTMX `GET` and swaps `#scatter-matrix-container`. On subsequent clicks, toggles CSS visibility only — no re-fetch.
- **Date/season change:** The date picker's `hx-target` targets the entire tab content wrapper. The swap resets both containers and reactivates the Table tab.

### CSS

New classes: `.comp-tab-strip`, `.comp-tab`, `.comp-tab--active`. Reuses existing color variables — no new design tokens.

---

## Matrix Visual Design

### Layout

- Cell size: **90×90 px**
- Full matrix: **720×720 px** (8 variables) inside a horizontal-scroll wrapper (`.comp-rankings-wrap` reused)
- Variable labels run along the diagonal cells (top-left to bottom-right)
- Mobile: horizontal scroll is acceptable — same pattern as the existing rankings table. No viewport warning needed.

### Cell Types

| Position | Content |
|---|---|
| **Diagonal** | Dark background, variable short label centered, value range (min–max of non-null values) as a small subtitle |
| **Lower triangle** | Scatter plot: X = column variable, Y = row variable. 3 minimal axis tick marks per axis, no tick labels. Dots colored by conference. |
| **Upper triangle** | Pearson r value printed as `+.87` or `−.32`. Background color encodes direction and magnitude (see color scale below). Hovering shows full r to 4 decimal places in tooltip. |

### Correlation r Color Scale

Background tint thresholds for upper-triangle cells:

| |r| range | Direction | Background |
|---|---|---|
| < 0.3 | any | Neutral (no tint) |
| 0.3 – 0.6 | positive | Light green tint |
| 0.3 – 0.6 | negative | Light red tint |
| 0.6 – 0.8 | positive | Medium green tint |
| 0.6 – 0.8 | negative | Medium red tint |
| > 0.8 | positive | Strong green tint |
| > 0.8 | negative | Strong red tint |

### Axis Treatment

Each scatter cell auto-scales X and Y to the data extent with 5% padding. Tick marks are rendered as short SVG lines with no labels — space is too tight at 90px. The tooltip provides exact values on hover.

### Conference Colors

Assigned from the following custom 33-color categorical palette, mapped deterministically by alphabetical sort of conference abbreviation:

```js
const categoricalColors = [
  "#1A6FD4", // 1  Cobalt
  "#4BAED4", // 2  Sky
  "#A0D4F0", // 3  Ice
  "#0C3060", // 4  Navy
  "#1D9E75", // 5  Teal
  "#5DCAA5", // 6  Seafoam
  "#0F6E56", // 7  Slate teal
  "#7DC232", // 8  Lime
  "#3B6D11", // 9  Forest
  "#97C459", // 10 Sage
  "#6B8E23", // 11 Olive
  "#C0DD97", // 12 Mint
  "#EFB527", // 13 Gold
  "#BA7517", // 14 Amber
  "#F5C030", // 15 Marigold
  "#F07A2A", // 16 Tangerine
  "#C0471A", // 17 Rust
  "#E07060", // 18 Coral
  "#C0272D", // 19 Crimson
  "#8B1A1A", // 20 Cherry
  "#7F77DD", // 21 Violet
  "#534AB7", // 22 Indigo
  "#AFA9EC", // 23 Lavender
  "#6A2BA0", // 24 Grape
  "#D4537E", // 25 Rose
  "#B0306A", // 26 Fuchsia
  "#ED93B1", // 27 Blush
  "#444441", // 28 Charcoal
  "#888780", // 29 Warm gray
  "#B4B2A9", // 30 Silver
  "#A0522D", // 31 Sienna
  "#8B7355", // 32 Taupe
  "#C8B89A", // 33 Sand
];
```

The same color is used consistently across all scatter cells and the legend.

---

## Interaction

### Tooltip

- **Trigger:** `mouseover` on a scatter dot, `mouseout` to hide
- **Content:** Team name (bold), conference, X-axis variable label + value, Y-axis variable label + value
- **Implementation:** Single absolutely-positioned `<div class="scatter-tooltip">` shown/hidden via JS. Follows cursor offset by 12px.
- **Correlation cells:** Hovering shows Pearson r to 4 decimal places and the variable pair labels (e.g., "Massey vs W%").

### Cross-Cell Team Highlight

- **Trigger:** Click any scatter dot
- **Effect:** That team's dot is enlarged (radius ×2), full opacity, with a gold stroke ring — across **every** scatter cell simultaneously. All other dots dim to 15% opacity.
- **Persistent label:** Team name shown in a `<div class="scatter-selected-label">` above the matrix while a selection is active.
- **Clear:** Click the same dot again, click any non-dot area within the matrix, or press Escape.
- **Implementation:** D3 data-join with a `data-team-id` attribute (team database ID) on each circle; toggling a CSS class `.scatter-dot--selected` / `.scatter-dot--dimmed` on `<svg>` root drives the highlight via CSS.

---

## Conference Legend

Rendered below the matrix (stacks to a single column on narrow viewports). Lists each conference present in the current data as a color swatch + abbreviation. Static — no interactive filtering. Used for color reference only.

---

## D3 Implementation Notes

- Load D3 v7 from CDN (`<script>` tag) in the fragment only when the Matrix tab is first activated. CDN dependency is acceptable for this feature.
- All rendering happens inside one self-invoking function to avoid polluting the global namespace.
- Pearson r computed in JS at render time — no server-side computation needed for ~350 teams × 28 pairs.
- Single SVG per cell (not one large SVG) for simplicity. Cells are laid out in CSS grid.
- The 8-variable order is hardcoded in the JS: `['winPct','ppg','opp','margin','rpi','massey','bt','btw']`.
- **Lazy rendering via `IntersectionObserver`:** Each cell `<div>` is initially empty. An `IntersectionObserver` watches all 64 cell containers and renders the SVG content only when a cell enters the viewport. This keeps the initial paint fast regardless of device capability.

---

## Templates

**New files:**
- `src/main/resources/templates/fragments/scatter-matrix.html` — HTMX fragment with data script, D3 mount point, D3 code, and legend

**Modified files:**
- `src/main/resources/templates/pages/comprehensive-rankings.html` — add tab strip, wrap existing container, add scatter matrix container
- `src/main/resources/static/css/main.css` — add `.comp-tab-*`, `.scatter-*` styles

---

## Out of Scope

- Replacing or modifying the existing Table tab behavior
- Server-side Pearson r computation
- Per-cell axis labels (space constraint)
- Interactive conference filtering via legend (deferred)
- Export/download of the matrix as an image
- Animation between date snapshots
- Including W and L as variables (subsumed by W%)
