# Scatter Plot Matrix — Design Spec
_Date: 2026-03-27_

## Overview

A scatter plot matrix (SPLOM) tab on the existing `/rankings/comprehensive` page. Plots every numeric column from the Comprehensive Rankings table against every other, allowing visual inspection of correlations across all 10 power-ranking variables for a given season and date.

---

## Variables

The 10 numeric columns from `ComprehensiveRankingRow`, in display order:

| Short label | Field | Format |
|---|---|---|
| W | `wins` | integer |
| L | `losses` | integer |
| W% | `winPct` | 3 decimal places |
| PPG | `meanPtsFor` | 1 decimal place |
| OPP | `meanPtsAgainst` | 1 decimal place |
| ± | `meanMargin` | 1 decimal place, signed |
| RPI | `rpi` | 4 decimal places |
| Massey | `masseyRating` | 2 decimal places, signed |
| B-T | `bradleyTerryRating` | 3 decimal places |
| BTW | `bradleyTerryWeightedRating` | 3 decimal places |

---

## Architecture

### Data Flow

1. User visits `/rankings/comprehensive` — existing page loads with **Table** tab active (unchanged behavior).
2. User clicks **Scatter Matrix** tab for the first time → HTMX fires `GET /rankings/comprehensive/{year}/scatter-matrix?date=`.
3. Fragment response contains:
   - A `<script id="scatter-data" type="application/json">` block with team data as a pre-rendered JSON string (controller serializes via Jackson `ObjectMapper`, passes as `String scatterDataJson` to model, rendered with `th:utext`).
   - A `<div id="scatter-matrix-root">` where D3 mounts.
   - A `<script>` that bootstraps D3 rendering once the fragment is in the DOM.
4. D3 reads the JSON, computes Pearson r for all 45 variable pairs, then renders the 10×10 grid.
5. Subsequent tab switches are pure CSS show/hide — no re-fetch.
6. Date picker or season selector change: HTMX swaps the entire tab content area, clearing both tabs and reverting to Table active.

### Data Serialization

Each team serializes to a flat JS object:

```json
{ "id": 42, "name": "Duke Blue Devils", "conf": "ACC",
  "w": 28, "l": 5, "winPct": 0.848,
  "ppg": 82.1, "opp": 65.3, "margin": 16.8,
  "rpi": 0.6421, "massey": 24.3, "bt": 1.842, "btw": 2.104 }
```

Null values serialize as `null`. D3 skips null-valued points in scatter plots (they are excluded from both rendering and Pearson r computation).

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

Internally: calls the existing `buildRows()` method, maps each `ComprehensiveRankingRow` to the flat JS object structure above, and adds `scatterRows` (the list) and `hasData` to the model. Returns `"fragments/scatter-matrix :: scatter-matrix"`.

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
- **Scatter Matrix tab:** On first click, fires HTMX `GET` and swaps `#scatter-matrix-container`. On subsequent clicks, JS checks whether `#scatter-matrix-root` has child nodes; if yes, toggles CSS visibility only — no re-fetch.
- **Date/season change:** The date picker's `hx-target` targets the entire tab content wrapper. The swap resets both containers and reactivates the Table tab.

### CSS

New classes: `.comp-tab-strip`, `.comp-tab`, `.comp-tab--active`. Reuses existing color variables — no new design tokens.

---

## Matrix Visual Design

### Layout

- Cell size: **90×90 px**
- Full matrix: **900×900 px** inside a horizontal-scroll wrapper (`.comp-rankings-wrap` reused)
- Variable labels run along the diagonal cells (top-left to bottom-right)

### Cell Types

| Position | Content |
|---|---|
| **Diagonal** | Dark background, variable short label centered, value range (min–max of non-null values) as a small subtitle |
| **Lower triangle** | Scatter plot: X = column variable, Y = row variable. 3 minimal axis tick marks per axis, no tick labels. Dots colored by conference. |
| **Upper triangle** | Pearson r value printed as `+.87` or `−.32`. Background color encodes direction and magnitude (green tint → positive, red tint → negative, neutral → near-zero). Hovering shows full r to 4 decimal places in tooltip. |

### Axis Treatment

Each scatter cell auto-scales X and Y to the data extent with 5% padding. Tick marks are rendered as short SVG lines with no labels — space is too tight at 90px. The tooltip provides exact values on hover.

### Conference Colors

Assigned from `d3.schemeTableau10` extended with additional categorical colors, mapped deterministically by alphabetical sort of conference abbreviation. Same color is used consistently across all scatter cells and the legend.

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
- **Implementation:** D3 data-join with a `data-team-id` attribute (team database ID) on each circle; toggling a CSS class `.scatter-dot--selected` / `.scatter-dot--dimmed` on `<svg>` root drives the highlight via CSS. Team ID is included in the serialized JS object (`id` field).

---

## Conference Legend

Rendered below the matrix (stacks to a single column on narrow viewports). Lists each conference present in the current data as a color swatch + abbreviation. Static — no interactive filtering. Used for color reference only.

---

## D3 Implementation Notes

- Load D3 v7 from CDN (`<script>` tag) in the fragment, only when the Matrix tab is first activated. No global page dependency.
- All rendering happens inside one self-invoking function to avoid polluting the global namespace.
- Pearson r computed in JS at render time — no server-side computation needed for ~350 teams × 45 pairs.
- Single SVG per cell (not one large SVG) for simplicity. Cells are laid out in CSS grid.
- The 10-variable order is hardcoded in the JS (matches the table column order): `['w','l','winPct','ppg','opp','margin','rpi','massey','bt','btw']`.

---

## Templates

**New files:**
- `src/main/resources/templates/fragments/scatter-matrix.html` — HTMX fragment with data script, D3 mount point, D3 code, and legend

**Modified files:**
- `src/main/resources/templates/pages/comprehensive-rankings.html` — add tab strip, wrap existing container, add scatter matrix container
- `src/main/resources/static/css/main.css` — add `.comp-tab-*`, `.scatter-*` styles

---

## Open Questions

1. **Cell size on smaller screens:** 90px × 10 = 900px minimum width. The table already requires horizontal scroll on mobile — the matrix will too. Should there be a minimum viewport warning ("best viewed on a wider screen") or is horizontal scroll acceptable as-is?

2. **Wins and Losses as variables:** W and L are largely redundant with W% (they're components of it), and L is almost perfectly negatively correlated with W. Including them makes the matrix denser and the most interesting correlations (RPI, Massey, B-T, BTW vs each other) harder to focus on. Should W and L be excluded, leaving 8 variables (64 cells), or kept for completeness?

3. **Conference color palette for 33 conferences:** D3's `schemeTableau10` only has 10 colors. There are ~33 Division I conferences. Mid-major conferences will share colors with others. Options: (a) accept color reuse for small conferences, (b) use a larger palette like `d3.schemeCategory10` + manual extensions, (c) color only the Power 6/major conferences distinctly and group the rest as "Other" in a neutral gray. Which approach?

4. **Null/missing data handling:** Teams with null Massey or B-T ratings (e.g., teams with very few games) are excluded from the scatter cells where those variables are axes. Should these teams be called out visually (e.g., a note like "X teams excluded from model rating cells due to missing data"), or silently dropped?

5. **D3 CDN vs. vendored:** Loading D3 v7 from CDN (~70 KB gzipped) means a network dependency. Should D3 be vendored into `src/main/resources/static/js/` to remove the CDN dependency, or is CDN acceptable for this feature?

6. **Correlation r color scale:** The background tint for upper-triangle cells encodes correlation magnitude. The exact color stops (at what r value does "strong" begin?) are TBD. Suggested: |r| < 0.3 → neutral, 0.3–0.6 → light tint, 0.6–0.8 → medium tint, > 0.8 → strong tint. Does this feel right or should the thresholds differ?

7. **Performance with 350 dots × 45 cells:** That's ~15,750 SVG circle elements on screen at once. D3 handles this fine on desktop, but lower-end devices may see slow initial render. Should there be a loading spinner while D3 renders, and/or should cells outside the visible viewport be rendered lazily (IntersectionObserver)?

---

## Out of Scope

- Replacing or modifying the existing Table tab behavior
- Server-side Pearson r computation
- Per-cell axis labels (space constraint)
- Interactive conference filtering via legend (deferred)
- Export/download of the matrix as an image
- Animation between date snapshots
