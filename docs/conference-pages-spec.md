# Conference Pages — Specification

**Status:** Implemented (steps 1–7 complete)
**Author:** Claude
**Date:** 2026-06-19

## 1. Goal

Add a public, season-scoped **Conferences** section to the web UI:

1. A **conference index** page (list of all conferences for a season).
2. An **individual conference** page that shows, for a given season:
   - Conference **standings**, with each team's **power ranking** shown alongside.
   - The **conference tournament** (bracket / results).
   - The conference's **average power rating** and its **rank among all conferences**.
   - The conference's **record vs. non-conference** opponents.
   - A summary of the conference's **NCAA tournament performance** (only rendered once the
     tournament has been played).

The top-nav already links to `/conferences` (`fragments/nav.html` line 15) and keys the active
state on `currentPage == 'conferences'`. **No web route currently serves `/conferences`** — only
the REST controller at `/api/conferences` exists. So the nav link is presently dead; this work
makes it live. No nav changes are required beyond confirming the link.

## 2. Scope & Non-Goals

In scope: two new server-rendered pages, one new `@Controller`, supporting repository queries,
DTOs, templates, and CSS. Read-only; no new entities, no migrations, no scraping changes.

Out of scope: editing conference membership, any admin tooling, REST API changes, historical
conference realignment visualizations, mobile-specific redesign beyond the existing responsive CSS.

## 3. Routing & Controller

New `ConferenceWebController` (`@Controller`), mirroring `TeamWebController` conventions.

| Method | Route | Returns | Purpose |
|--------|-------|---------|---------|
| GET | `/conferences` | `pages/conferences` | Index of all conferences for the resolved season |
| GET | `/conferences/{id}` | `pages/conference-detail` | Full detail for one conference (latest season) |
| GET | `/conferences/{id}/season/{year}` | `fragments/conference-season :: panel` | HTMX swap of the detail body for a different season |

- Season resolution mirrors existing pages: optional `?year=` on the index; default to
  `seasonRepository.findTopByOrderByYearDesc()`. The detail page defaults to the latest season in
  which the conference has a membership, with an HTMX season selector for prior seasons (matching
  the team-detail lazy-load pattern at `TeamWebController#teamSeasonSchedule`).
- All controller methods set `model.addAttribute("currentPage", "conferences")`.
- Unknown id/year → `EntityNotFoundException` (handled globally → 404), consistent with
  `TeamWebController`.

## 4. Data Sources (all existing — no schema changes)

| Need | Source |
|------|--------|
| Conference list / metadata | `ConferenceRepository`, `Conference` (name, abbreviation, logoUrl) |
| Members of a conference in a season | `ConferenceMembershipRepository.findByConferenceIdAndSeasonId` |
| Standings (W-L, conf W-L, home/road, points, streak, standing) | `SeasonStatistics` (prefer `calc*` fields, fall back to scraped, via the `resolveInt` pattern) |
| Power rating + rank per team | `TeamPowerRatingSnapshotRepository.findBySeasonModelAndDate` at the latest snapshot date |
| Conference tournament games | `GameRepository` filtered to `tournamentType = CONFERENCE_TOURNAMENT` for the season + member teams |
| Non-conference record | `Game.conferenceGame` flag over member teams' regular-season games |
| NCAA tournament results | `GameRepository.findBySeasonIdAndTournamentTypeWithDetails(seasonId, NCAA_TOURNAMENT)` filtered to member teams |

**Default power model:** Massey (Margin), i.e. `MasseyRatingService.MODEL_TYPE` — same default the
comprehensive rankings page uses. The latest snapshot date for the season is resolved via
`TeamPowerRatingSnapshotRepository.findLatestSnapshotDate(seasonId, modelType)`.

A new repo query is needed: fetch the member `Team`s of a conference for a season. We can reuse
`ConferenceMembershipRepository.findByConferenceIdAndSeasonId` (already exists) and pull teams from
the memberships. For efficiency a `JOIN FETCH cm.team` variant may be added.

## 5. Page Designs

### 5.1 Conference Index (`/conferences`)

Header: "Conferences", season label, conference count.

A sortable table (or card grid, see open questions) with one row per conference for the season:

| Column | Source |
|--------|--------|
| Logo + name (links to detail) | `Conference` |
| # teams | membership count |
| Avg power rating | mean of member teams' Massey rating at latest snapshot |
| Conference rank | dense rank of avg power rating across all conferences (1 = strongest) |
| Combined record | sum of member W-L (overall) |
| Non-conf record | sum of member non-conference W-L |

Conferences with no rated members sort last; the rank is computed only over conferences that have
at least one rated team.

### 5.2 Conference Detail (`/conferences/{id}`)

Header block:
- Conference logo + name, season selector (HTMX).
- Summary stat strip: **Avg power rating**, **Rank among conferences** (e.g. "#3 of 32"),
  **Non-conference record**, **# NCAA bids** (only after Selection Sunday).

**Standings table** (the centerpiece), sorted by conference standing:

| Column | Notes |
|--------|-------|
| # | conference standing (prefer `conferenceStanding`; fall back to sort by conf win%, then overall win%) |
| Team (logo + name, links to team page) | |
| Conf W-L | `calcConferenceWins/Losses` → scraped fallback |
| Overall W-L | `calcWins/Losses` → scraped fallback |
| Home / Road | from `calc*`/scraped splits |
| Streak | signed streak rendered as W3 / L2 |
| **Power rating** | Massey rating at latest snapshot (2 decimals) |
| **Power rank** | national rank from the snapshot (e.g. "#14") |

**Conference tournament section:** games with `tournamentType = CONFERENCE_TOURNAMENT` for the
season, restricted to member teams. Rendered as **results grouped by round** (round order from the
`ROUND_ORDER` list already in `TeamWebController` — Quarterfinal → Semifinal → Final), each row
showing seed, teams, score, and W/L highlight. Champion called out at the top once the Final is
FINAL. (Full bracket-tree rendering is an open question — see §8.) Section hidden if no conference
tournament games exist for the season.

**Non-conference performance:** combined non-conference record, optionally split by result quality
(e.g. record, win %, maybe vs. other power conferences — open question). Computed by iterating
member teams' FINAL games where `conferenceGame == false` and the game is not a
conference-tournament game.

**NCAA tournament summary** (rendered only when the season has NCAA tournament games):
- Number of bids (distinct member teams appearing in `NCAA_TOURNAMENT` games).
- Combined W-L in the tournament.
- Per-team furthest round reached (reusing the `roundIndex`/`ROUND_ORDER` logic).
- Whether a member team won the national championship.
- A small list of member teams with seed and result/exit round.

## 6. DTOs (controller-local records, following `TeamWebController` style)

- `ConferenceSummary(id, name, abbreviation, logoUrl, teamCount, avgPowerRating, conferenceRank, wins, losses, nonConfWins, nonConfLosses)`
- `ConferenceStandingRow(teamId, teamName, logoUrl, confWins, confLosses, wins, losses, homeW, homeL, roadW, roadL, streak, powerRating, powerRank)`
- `ConferenceTournamentRound(roundName, List<GameRow>)` (reuse/adapt the existing `GameRow` shape)
- `NcaaTournamentSummary(bidCount, wins, losses, champion?, List<TeamTournamentLine>)`
- `ConferenceDetail(year, Conference, summary, List<ConferenceStandingRow>, tournament, ncaa)`

## 7. Computation Details

- **Power rank "of N":** N = field size from the power-rating snapshot (count of rated teams that
  date). Rank is the snapshot's own `rank` field (national).
- **Conference avg rating & conference rank:** computed across *all* conferences for the season so
  the same number is consistent on the index and detail pages. Recommend a shared service method
  (e.g. `ConferenceRankingService`) so both pages agree. Averaging uses member teams that have a
  rating on the latest snapshot date; conferences are dense-ranked by that average descending.
- **Non-conference record:** for each member team, count FINAL games with `conferenceGame == false`
  AND (`tournamentType` null OR `IN_SEASON_TOURNAMENT`) as regular-season non-conf. (Postseason
  tournaments excluded — see open question on whether NCAA/NIT should count.)
- **Standings sort:** use `conferenceStanding` when present and non-null for all rows; otherwise
  derive by conf win% desc, then overall win% desc, then name.

## 8. Open Questions

1. **Conference tournament rendering:** Simple **results-grouped-by-round list** (recommended,
   robust to messy data), or a full **bracket tree** like the NCAA bracket? The NCAA
   `BracketService` is seed-driven and NCAA-specific; conference tournaments have irregular sizes
   (4–16 teams, byes), so a true bracket is materially more work and more fragile. Default: list.
<br/>**Commenr** Just list the gaes with scores.  If the round is not named, just group them by dates.  Do not try to construct a bracket.
2. **"Average power ranking":** average of the **rating value** (recommended — comparable magnitude)
   or average of each team's **national rank** (lower = better)? Default: average rating, then rank
   conferences by it. Either way the page can also show "avg national rank" as a secondary stat.
<br/>**Comment** Simple average of values
3. **Which power model** is the canonical one for these pages? Default: **Massey (Margin)**,
   matching the rankings page. Should the user be able to switch models (Massey/BT/etc.) on the
   conference page, or is one model enough?
<br/>**Comment** Massey (Margin) is the canonical model for these pages
4. **Non-conference record definition:** regular-season non-conference only (recommended), or should
   postseason non-conference games (NCAA/NIT/CBI/Crown) also fold into the "vs non-conference"
   number? Default: regular season + in-season tournaments only; postseason reported separately in
   the NCAA section.
<br>**comment** Go with your Default.
5. **Index page layout:** sortable **table** (recommended — supports the rank/avg-rating columns
   well) or a **card grid** like the Teams page? Default: table.
<br>**Comment**table
6. **Season selector scope:** show all seasons the conference has memberships for, or only seasons
   that also have power-rating snapshots? Default: all membership seasons, gracefully showing "—"
   for power columns when snapshots are absent.
<br>**Comment**Defauult

7. **NCAA summary timing:** gate purely on "NCAA tournament games exist for the season" (recommended)
   vs. a date check. Default: presence of games. Before that, the section and the "# bids" stat are
   hidden.

8. **Conference tournament champion / auto-bid:** Should the page explicitly label the conference
   tournament champion (and note they earned the NCAA auto-bid)? Default: yes, label the champion;
   no explicit auto-bid claim (we don't model bids beyond games).

9. **Independents / no-conference teams:** Should there be a pseudo "Independents" entry on the
   index (teams with no conference membership)? Default: no — index lists real conferences only.

## 9. Resolved Decisions

Per review comments:

1. **Conference tournament:** No bracket. List the games with scores. Group by **named round**
   when present; when the round is unnamed, **group by date**. Champion still labeled once the
   last game is FINAL.
2. **Average power ranking:** **Simple average** of member teams' Massey rating values; conferences
   dense-ranked by that average (desc).
3. **Power model:** **Massey (Margin)** is the single canonical model. No model switcher.
4. **Non-conference record:** Default — regular-season + in-season-tournament games only
   (`conferenceGame == false`, `tournamentType` null or `IN_SEASON_TOURNAMENT`). Postseason
   reported only in the NCAA section.
5. **Index layout:** **Table.**
6. **Season selector:** Default — all seasons the conference has memberships for; power columns
   show "—" when no snapshot exists.
7. **NCAA summary timing:** Default — gate on presence of `NCAA_TOURNAMENT` games for the season.
8. **Champion label:** Default — label the conference-tournament champion; no auto-bid claim.
9. **Independents:** Default — index lists real conferences only.

## 10. Implementation Plan

Ordered, each step independently compilable. No DB migrations.

### Step 1 — Repository queries

**`ConferenceMembershipRepository`** — add a fetch-join variant so we get member `Team`s in one query:
```java
@Query("SELECT cm FROM ConferenceMembership cm JOIN FETCH cm.team " +
       "WHERE cm.conference.id = :conferenceId AND cm.season.id = :seasonId")
List<ConferenceMembership> findByConferenceIdAndSeasonIdWithTeam(Long conferenceId, Long seasonId);
```

**`GameRepository`** — two additions:
- `findBySeasonIdWithTeams(seasonId)` — FINAL/all games for a season with `JOIN FETCH homeTeam/awayTeam` (drives the index's season-wide non-conf aggregation in one pass).
- `findBySeasonAndTeamIds(seasonId, Collection<Long> teamIds)` — member games for the detail page (`homeTeam.id IN :ids OR awayTeam.id IN :ids`), with team + odds fetch joins.

### Step 2 — `ConferenceRankingService` (new, in `service/`)

Single source of truth so index and detail agree. One method:
```java
Map<Long, ConferenceAggregate> aggregateBySeason(Season season);
```
`ConferenceAggregate(confId, teamCount, avgMasseyRating /*nullable*/, conferenceRank /*nullable*/,
wins, losses, nonConfWins, nonConfLosses)`.

Computation (all for the resolved season):
- Members per conference from `ConferenceMembership` for the season.
- Massey ratings: `findBySeasonModelAndDate(seasonId, MasseyRatingService.MODEL_TYPE, latestDate)` → `team→rating`.
- `avgMasseyRating` = simple mean of member ratings that exist; conferences with ≥1 rated team are dense-ranked desc to assign `conferenceRank`; unrated conferences get `null` rank (sort last).
- Combined W-L: sum member `SeasonStatistics` (calc→scraped fallback).
- Non-conf W-L: single pass over `findBySeasonIdWithTeams`; for each FINAL game with
  `conferenceGame == false` and (`tournamentType` null or `IN_SEASON_TOURNAMENT`), credit each
  participating team's conference with a W or L.

### Step 3 — Shared tournament helper (small refactor)

Extract `ROUND_ORDER` + `roundIndex(...)` from `TeamWebController` into a package-private util
(`controller/TournamentRounds.java`) and have both `TeamWebController` and the new controller use
it. Low risk, keeps round ordering consistent. (If preferred, duplicate the constant instead —
flagged as optional.)

### Step 4 — `ConferenceWebController` (new) + DTOs

Routes from §3. DTOs from §6 (controller-local records), plus a lightweight
`TournamentGameRow(date, round, topTeam, topSeed, topScore, bottomTeam, bottomSeed, bottomScore, winnerSide)`
for the tournament/NCAA listings — does **not** reuse the heavier team-page `GameRow` (no spreads
needed here).

- `/conferences` → build `ConferenceSummary` rows from `ConferenceRankingService` + conference
  metadata; sort by `conferenceRank` (unranked last). View `pages/conferences`.
- `/conferences/{id}` → resolve latest membership season; delegate body to a private
  `buildDetail(conference, season)`; also pass the list of selectable seasons. View
  `pages/conference-detail`.
- `/conferences/{id}/season/{year}` → `buildDetail` only; return `fragments/conference-season :: panel`.

`buildDetail` assembles: standings rows (members + `SeasonStatistics` + power snapshot rank/rating),
the summary strip (from the aggregate), the tournament section (member `CONFERENCE_TOURNAMENT`
games grouped by round/date, champion resolved from the last FINAL game), and the NCAA summary
(only when `NCAA_TOURNAMENT` games exist for the season).

### Step 5 — Templates

- `pages/conferences.html` — sortable table (logo+name link, #teams, avg rating, conf rank,
  combined record, non-conf record). Mirror `pages/teams.html` header/markup conventions.
- `pages/conference-detail.html` — header + season selector (HTMX `hx-get` to the season fragment,
  matching team-detail), summary strip, standings table, tournament section, NCAA section.
- `fragments/conference-season.html` — `th:fragment="panel"` wrapping the detail body for HTMX swap.

### Step 6 — CSS

Add a conference section to `src/main/resources/static/css/main.css`, reusing existing table /
header / card primitives where possible (e.g. the standings table can lean on existing table
styles; add `conference-*` classes only where needed).

### Step 7 — Tests

- `ConferenceRankingServiceTest` (or integration test) covering avg/rank, unranked handling, and
  non-conf counting (extends `BaseIntegrationTest`).
- `ConferenceWebControllerTest` — 200 for index + detail, 404 for unknown id/year, season-fragment
  swap returns the panel, NCAA section hidden when no tournament games. Follow existing web-controller
  test patterns and the FK-safe cleanup order.

### Risk / Notes

- Highest-touch existing file is `TeamWebController` (Step 3 extraction) — optional and isolated.
- All other changes are additive (new controller/service/templates/queries).
- Performance: index does 1 ratings query + 1 season-games query + membership/stats queries — no
  N+1 across conferences.
