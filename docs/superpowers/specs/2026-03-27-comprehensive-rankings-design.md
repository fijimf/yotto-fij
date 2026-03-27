# Comprehensive Rankings Page — Design Spec
_Date: 2026-03-27_

## Overview

A new page at `/rankings/comprehensive` that presents all ranking data for every team in a single, sortable, filterable table. Lives alongside the existing `/rankings` page as a parallel experiment. The two pages coexist while users indicate which they prefer.

---

## Data & Architecture

### Sources

Two snapshot tables joined by team, for a given season + date:

| Source | Fields used |
|---|---|
| `TeamSeasonStatSnapshot` | wins, losses, winPct, meanPtsFor, meanPtsAgainst, meanMargin, rpi |
| `TeamPowerRatingSnapshot` (model=`MASSEY`) | rating → massey |
| `TeamPowerRatingSnapshot` (model=`BRADLEY_TERRY`) | rating → bradleyTerry |
| `TeamPowerRatingSnapshot` (model=`BRADLEY_TERRY_W`) | rating → bradleyTerryWeighted |

Conference comes from `SeasonStatistics` (joined by team+season).

### DTO: `ComprehensiveRankingRow`

Assembled in the controller by fetching all four sources for the resolved date and merging by `team.id`. Fields:

```
team (Team)
conferenceName (String)
conferenceAbbr (String)
wins (Integer)
losses (Integer)
winPct (Double)
meanPtsFor (Double)
meanPtsAgainst (Double)
meanMargin (Double)
rpi (Double)
masseyRating (Double)        — null if no snapshot
bradleyTerryRating (Double)  — null if no snapshot
bradleyTerryWeightedRating (Double) — null if no snapshot
```

Missing ratings render as `—` in the template.

### Date Resolution

Available dates come from `TeamPowerRatingSnapshotRepository.findSnapshotDates(seasonId, MASSEY)` — same as the existing rankings page. Default: latest available date.

---

## Routes

| Method | Path | Description |
|---|---|---|
| `GET` | `/rankings/comprehensive` | Full page. Params: `?year=` (optional), `?date=` (optional) |
| `GET` | `/rankings/comprehensive/{year}/table` | HTMX fragment. Param: `?date=` |

Both follow the exact same season/date resolution pattern as `RankingsPageController`.

---

## Controller

New `ComprehensiveRankingsController` (does not modify `RankingsPageController`). Injects:
- `SeasonRepository`
- `TeamPowerRatingSnapshotRepository`
- `TeamSeasonStatSnapshotRepository`
- `SeasonStatisticsRepository`

The `populateModel` method:
1. Fetches stat snapshots for the resolved date
2. Fetches each of the three power rating model snapshots
3. Builds a `Map<Long, ComprehensiveRankingRow>` keyed by team ID, merging all four sources
4. Sorts by Massey rating descending (server-side default; client can re-sort)
5. Adds `rows`, `season`, `allSeasons`, `selectedDate`, `availableDates`, `latestDate`, `hasData` to model

---

## Page Structure

```
/rankings/comprehensive
├── Page header: "2026 Comprehensive Rankings"
│   └── Season selector dropdown (top-right)
├── Date controls row: "As of:" date picker + "Latest: YYYY-MM-DD"
├── Filter bar: [Team name input] [Conference dropdown]
└── HTMX-swappable container
    └── fragments/comprehensive-rankings-table :: comp-rankings-table
        └── Full-width grouped-header table (horizontal scroll wrapper)
```

### Navigation

Add `"Comp. Rankings"` as a new `<li>` in `nav.html`, immediately after the existing "Rankings" link. `currentPage` value: `"comprehensive-rankings"`.

---

## Table Design (Approach B — Grouped Headers)

### Column Groups

| Group | Columns | Header tint |
|---|---|---|
| Team | #, Team, Conf | neutral (no tint) |
| Record | W, L, W% | blue tint |
| Scoring | PPG, OPP, ± | green tint |
| Model Ratings | RPI, Massey, B-T, BTW | purple tint |

### Header rows

- **Row 1 (group labels):** Team / Record / Scoring / Model Ratings — spanning `colspan`, color-coded border-bottom matching group tint
- **Row 2 (column names):** individual abbreviated column headers, clickable for sort

### Typography

- Table base font: `0.71rem`
- Header labels: `0.64rem`, uppercase, letter-spacing
- Row padding: `5px 7px`
- Positive margins/ratings: `--color-success` green
- Negative margins/ratings: `--color-danger` red
- `font-variant-numeric: tabular-nums` on all numeric cells

### Sticky columns

Rank (`#`) and Team columns are `position: sticky` so they remain visible during horizontal scroll. Requires `overflow-x: auto` wrapper on the table container.

### Mobile

No special mobile layout — horizontal scroll is acceptable. The sticky team column keeps context. A small note ("scroll →") may help on first load.

---

## Sorting & Filtering (Client-side JS)

All logic in inline `<script>` in the Thymeleaf template (or a small dedicated JS file).

### Sorting

- Click a `<th>` in the column row → sort table rows by that column
- Toggle asc/desc on repeated clicks; active column shows `▲`/`▼` indicator
- Default sort on page load: Massey descending
- Numeric columns: parse float, `null`/`—` sort last
- Text columns (Team, Conf): case-insensitive locale compare

### Filtering

- **Team name input:** `input` event, case-insensitive substring match on team name; hide non-matching rows
- **Conference dropdown:** built from unique conference values in the data; `change` event hides rows not matching
- Both filters compose (both active at once = AND)

---

## Templates

**New files:**
- `src/main/resources/templates/pages/comprehensive-rankings.html`
- `src/main/resources/templates/fragments/comprehensive-rankings-table.html`

**Modified files:**
- `src/main/resources/templates/fragments/nav.html` — add nav link
- `src/main/resources/static/css/main.css` — add `.comp-rankings-*` styles

---

## CSS Classes (new)

```
.comp-rankings-wrap          — overflow-x: auto wrapper
.comp-rankings-table         — the <table>
.comp-rankings-group-row     — first header row (group labels)
.comp-rankings-col-row       — second header row (column names)
.comp-rankings__g-{name}     — group tint classes: identity, record, scoring, ratings
.comp-rankings__sort-active  — highlighted sort column header
.comp-rankings__team-cell    — flex row with dot + name
.comp-rankings__team-dot     — team color dot
```

Reuses existing: `.card`, `.page-header`, `.form-input`, `.form-input--sm`, `.analytics-date-row`, `.rankings-rating--pos`, `.rankings-rating--neg`

---

## Out of Scope

- Server-side sorting/pagination (table is ~350 rows max, client-side is fine)
- Per-column filter inputs (Approach C — deferred)
- Replacing the existing `/rankings` page
- Mobile-optimized layout
