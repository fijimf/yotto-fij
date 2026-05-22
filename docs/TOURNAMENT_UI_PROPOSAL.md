# Tournament Identification — UI Proposal (Phase 6)

Status: **deferred**. The underlying data (six new columns on `Game`) lands in Phases 1–4. This document captures candidate UI work to do once those columns are populated. Each section is independently scopeable.

## Data available

After Phases 1–4 every `Game` row carries:

- `tournament_type` — `NCAA_TOURNAMENT`, `NIT`, `CBI`, `CROWN`, `CONFERENCE_TOURNAMENT`, `IN_SEASON_TOURNAMENT`, `OTHER_POSTSEASON`, or `null` (regular season)
- `tournament_name` — sponsor-stripped, e.g. `"ACC Tournament"`, `"NCAA Tournament"`, `"NIT"`, `"Maui Invitational"`
- `tournament_round` — e.g. `"1st Round"`, `"Quarterfinal"`, `"Sweet 16"`, `"Final Four"`, `"National Championship"`
- `tournament_region` — NCAA only: `"East"`, `"West"`, `"South"`, `"Midwest"`
- `espn_season_type` — `2` or `3`
- `espn_note_raw` — verbatim ESPN label for debugging / re-classification

## Candidate UI work

### 1. Tournament badge on game cards / schedules

On `team/{slug}` schedule rows and `/games/{id}` detail headers, add a small badge.

```
NCAA 1R · Midwest      [NCAA tournament games]
Big East QF            [conference tournament]
NIT 1R                 [NIT]
Maui Invitational SF   [in-season]
```

Format: `<short-name> <short-round>` with a tooltip showing the full `tournament_name` and `tournament_round`.

**Where:** `templates/team/_schedule-row.html`, `templates/game/_header.html`.
**Effort:** small (CSS + a Thymeleaf helper that maps enum -> short label).

### 2. Postseason / regular-season filter on rankings

`/rankings` and the team page records currently aggregate all games. Add a segmented control:

- **All games** (default)
- **Regular season** (excludes everything where `tournament_type IS NOT NULL` or restrict to `tournament_type IS NULL OR tournament_type = 'IN_SEASON_TOURNAMENT'` — depends on whether holiday tournaments count as "regular season")
- **Postseason only** (`tournament_type IN ('NCAA_TOURNAMENT','NIT','CBI','CROWN','CONFERENCE_TOURNAMENT','OTHER_POSTSEASON')`)

**Where:** `ComprehensiveRankingsController`, `StatisticsController`, `TeamWebController`.
**Effort:** medium — repository methods + URL query params + view toggles. Touches enough surface area to warrant its own PR.

### 3. Per-team postseason record column

On the team page, add a row breaking down record by tournament:

```
Conference tournament   2-1
NCAA Tournament         3-1   (Sweet 16)
```

Furthest-round-reached can be derived from `MAX(tournament_round)` with an ordered enum mapping (1st Round < 2nd Round < Sweet 16 < Elite 8 < Final Four < National Championship).

**Where:** `TeamWebController.teamPage()`, `templates/team/team.html`.
**Effort:** small-medium. Needs a round-order helper.

### 4. Admin dashboard: postseason tie-out

Mirror the existing non-DI tie-out tile. Per season:

- Conference tournament games scheduled / final
- NCAA tournament games scheduled / final (expected: 67, including First Four)
- NIT / CBI / Crown counts

Anomalies (e.g., NCAA count != 67 after the championship game) get a yellow chip.

**Where:** `AdminController`, `templates/admin/dashboard.html`.
**Effort:** small. Read-only counts.

### 5. Bracket view (stretch)

For each `Season` + `tournament_type = NCAA_TOURNAMENT`, render a static SVG bracket by region using `tournament_region` + `tournament_round`. Conference tournaments would render as smaller single-region brackets.

**Effort:** large (new template + SVG layout logic). Worth scoping separately if there's demand.

## Suggested order

1. **Badge on game cards** — cheapest, biggest visible win.
2. **Admin tie-out tile** — confirms the data backfilled correctly.
3. **Postseason filter on rankings** — biggest analytical lift.
4. **Per-team postseason record** — natural follow-on to #3.
5. **Bracket view** — only if there's actual demand.

## Open questions before starting any of this

- For "regular season" filter: do in-season tournaments (Maui, Champions Classic) count as regular season or as their own bucket? Affects #2.
- Should the badge include a sponsor when present (e.g. show `"Phillips 66 Big 12 QF"` vs `"Big 12 QF"`)? Currently we only persist the stripped name; raw is available via `espn_note_raw`.
- Bracket view: men's only, or do we ever plan to add women's? Schema would need a `division` field if so.
