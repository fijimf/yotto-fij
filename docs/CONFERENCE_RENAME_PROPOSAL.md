# Season-Scoped Conference Names — Proposal

Status: **implemented** (July 2026) as proposed below. Landed as:

- `V23__conference_name_history.sql` — table + WAC seed row (`espn_id = '30'`, verified in
  the DB; old logo URL hardcoded so a prior scrape can't corrupt the seed).
- `ConferenceNameHistory` entity + repository; `ConferenceNamingService` with a
  one-query `ConferenceNames` snapshot (`load()`) for loop-heavy call sites and a
  `resolve()` convenience for single lookups. Pure unit tests in
  `ConferenceNamingServiceTest`.
- Rename-detection WARN in `ConferenceScraper` (update still applied; history rows are
  never auto-created — see `ConferenceScraperTest`).
- Season-resolved identity threaded through all six display sites. The conference-detail
  hero swaps name/abbr/logo out-of-band (`hx-swap-oob`) when the season tab changes,
  gated on an `oobHero` model flag so the inline render doesn't duplicate it.
- `GET /api/conferences/{id}` now returns a `nameHistory` array
  (`ConferenceResponse` DTO; other endpoints unchanged).
- `conference_name_history` added to the test-suite truncate list; MockMvc coverage in
  `ConferenceWebControllerTest`/`ConferenceControllerTest`.

## Problem

The WAC has rebranded to the **United Athletic Conference (UAC)** effective with the
2026-27 season (`Season.year = 2027`; per convention, `year` is the season's *ending*
year — `StandingsScraper.java:164` sets `startDate = Nov 1 of year − 1`). It is the same
conference — same ESPN `groupId`, same membership continuity — but it should display as
"Western Athletic Conference / WAC" on 2026-and-earlier pages and "United Athletic
Conference / UAC" from 2027 on.

Today the model cannot represent this:

- `Conference` holds a **single** `name`/`abbreviation` shared across all seasons
  (`Conference.java:22-26`), with `UNIQUE` constraints on both the entity
  (`@Column(unique = true)`) and the DB (`V1__initial_schema.sql` line 7).
- Season scoping exists only for *membership* (`ConferenceMembership`, unique per
  team+season) — not for the conference's display identity.
- `ConferenceScraper` upserts by `espnId` and **unconditionally overwrites**
  `name`/`abbreviation` on every run (`ConferenceScraper.java:59-65`). The next
  conferences scrape after ESPN relabels group X will silently rename the row —
  retroactively rebranding every historical season to UAC.

One piece of good news from auditing the schema: **no snapshot or stats table stores the
conference name as text**. `season_statistics`, `season_population_stats`, etc. all point
at `conference_id`; names are resolved live at render time. So this is an app-layer +
one-small-table change, not a data-backfill project.

## Proposed solution: a conference name-history table

Keep exactly one `conferences` row per conference (identity, `espnId`, memberships, and
stats continuity are untouched). `conferences.name`/`abbreviation` continue to hold the
**current** branding. Add a small table recording **superseded** brandings and the last
season each applied to:

```sql
-- V23__conference_name_history.sql
CREATE TABLE conference_name_history (
    id               BIGSERIAL PRIMARY KEY,
    conference_id    BIGINT NOT NULL REFERENCES conferences(id) ON DELETE CASCADE,
    name             VARCHAR(255) NOT NULL,
    abbreviation     VARCHAR(50),
    logo_url         VARCHAR(500),
    last_season_year INT NOT NULL,          -- inclusive: this branding applies through this Season.year
    CONSTRAINT uq_conf_name_history UNIQUE (conference_id, last_season_year)
);

-- Seed the WAC era: applies through the 2025-26 season (year 2026)
INSERT INTO conference_name_history (conference_id, name, abbreviation, logo_url, last_season_year)
SELECT id, 'Western Athletic Conference', 'WAC', logo_url, 2026
FROM conferences WHERE espn_id = '<WAC groupId>';   -- resolve the actual espn_id when implementing
```

### Resolution rule

For a requested `(conference, seasonYear)`:

1. Find history rows for the conference with `last_season_year >= seasonYear`.
2. If any exist, use the one with the **smallest** such `last_season_year`.
3. Otherwise fall back to the canonical `conferences.name` / `abbreviation` / `logo_url`.

This composes correctly across multiple renames. Example: rows `(…WAC…, ≤2026)` and a
hypothetical later `(…UAC…, ≤2035)` → year 2024 resolves to WAC, 2030 to UAC (via the
≤2035 row), 2040 to whatever the canonical row then says. History rows carry an optional
`logo_url` because rebrands usually change the mark too; null falls back to the canonical
logo.

### New code

- **Entity + repository**: `ConferenceNameHistory` (`@ManyToOne Conference`, name,
  abbreviation, logoUrl, lastSeasonYear), `findByConferenceIdOrderByLastSeasonYearAsc`.
- **`ConferenceNamingService`** — one public method, roughly
  `ConferenceIdentity resolve(Conference c, int seasonYear)` returning
  `record ConferenceIdentity(String name, String abbreviation, String logoUrl)`. The
  table will have a handful of rows ever; load-all-and-filter per request is fine (or a
  trivial in-memory cache — renames change only via migration/admin action).
- **Scraper guard** (`ConferenceScraper`): before overwriting, compare the incoming name
  to the existing one. On mismatch, log a **WARN** ("conference <espnId> renamed
  '<old>' → '<new>'; if this is a rebrand, add a conference_name_history row") and still
  apply the update. Renames are rare enough that a human-in-the-loop warning beats
  auto-snapshotting — ESPN also makes trivial label tweaks (punctuation, sponsor names)
  that must *not* mint a fake historical era.

### Display call sites to thread the season through

All of these already know which season they're rendering; they just currently read the
un-scoped `conference.getName()`:

| Site | Change |
|---|---|
| `ConferenceWebController` detail hero + `<title>` (`conference-detail.html:7,19-21`) | Resolve name for the **selected** season; the hero should update when the season picker changes (move name/abbr into the HTMX season panel's model, or swap the hero text alongside it) |
| `ConferenceWebController` index (`/conferences?year=`) | Resolve per row for the requested year |
| `TeamWebController` per-season schedules (`SeasonSchedule.conferenceName`, lines 160, 198) and teams-page grouping (lines 90-116) | Resolve via the row's season; grouping/sorting by resolved name is per-season-consistent, so no further change |
| `ComprehensiveRankingsController:188-192` | Resolve via the page's season. The rankings filter dropdown keys on the abbreviation *string* (`comprehensive-rankings-table.html:24-26,191-201`) — still consistent because the whole page is one season |
| `GameDetailController:111-115` (conference-game header) | Resolve via the game's season |
| `AdminQaController:83,139` | Resolve via the QA run's season |

REST API (`/api/conferences`): keep serializing the canonical current name (no breaking
change), and additionally expose the history — either a `nameHistory` array on
`GET /api/conferences/{id}` or an optional `?season=` query that returns the resolved
identity. Lean toward the array; it's self-describing and cache-friendly.

### Interactions with name-uniqueness

- `conferences.name UNIQUE` stays — still one row per conference.
- History names get **no** uniqueness constraint against `conferences.name` (a defunct
  conference's old name could in principle be adopted by another; don't over-constrain).
- `ConferenceService.create`'s `existsByName` check and `findByName` lookups keep their
  current semantics (canonical names only). Optionally warn on creating a conference
  whose name collides with a historical one — nice-to-have, not required.

## Alternatives considered

1. **Second `Conference` row for UAC.** Rejected. ESPN keeps the same `groupId`, so the
   `espnId`-keyed upsert would fight over one row (or need a synthetic espnId hack);
   memberships/stats would fragment across two ids, breaking team-page conference
   history, conference-detail season pickers (`findSeasonsByConferenceId`), and z-score
   grouping. The user-facing truth is that it's one conference.
2. **Just rename in place (status quo).** Rejected — rewrites history; 2015 standings
   pages would say "United Athletic Conference," which is exactly the bug.
3. **Full SCD-2 versioning of the conferences table** (valid-from/valid-to on every
   attribute). Overkill for an event that happens to a conference once a decade; the
   history table gets the same result with one small side table and no churn in every
   query that touches `Conference`.
4. **Name columns on `ConferenceMembership`.** Wrong grain — duplicates the name per
   *team* per season and leaves no home for the name in seasons where memberships
   haven't been scraped yet.

## Implementation order

1. `V23__conference_name_history.sql` — table + WAC seed row (verify the WAC's actual
   `espn_id` in the DB first; also confirm what ESPN's API currently returns for that
   groupId so we know whether the canonical row should be renamed to UAC in the same
   migration or left for the next conferences scrape).
2. Entity, repository, `ConferenceNamingService` + unit tests (resolution ordering,
   fallback, multi-rename case).
3. Scraper rename-detection WARN + test (`@MockBean EspnApiClient` per existing pattern).
4. Thread season-resolved identity through the six display sites above; template updates.
5. API history exposure.
6. Integration test: seed WAC history row, assert 2026 pages render WAC and 2027 pages
   render UAC. Note for the test suite: `conference_name_history` cascades on conference
   delete, but add it to the FK-safe `@BeforeEach` cleanup order (before `conferences`)
   to match the existing convention.

## Open questions

- Confirm ESPN keeps `groupId` stable through the rebrand (expected — they did for
  similar rebrands like Pac-12→Pac-12 membership collapses; verify against the live
  scoreboard API before relying on it).
- Does the 2026-27 UAC logo differ from the WAC logo on ESPN? If yes, the seeded history
  row must capture the *old* logo URL before the next scrape overwrites
  `conferences.logo_url`.
- Should the admin UI get a small "name history" editor on a conference page, or is
  migration-only management acceptable given how rare this is? (Proposal assumes
  migration-only for now.)
