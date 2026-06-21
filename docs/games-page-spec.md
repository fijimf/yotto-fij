# Games Page — Specification

**Status:** Draft for review (no code written yet)
**Author:** Claude
**Date:** 2026-06-21

## 1. Goal

Make the **Games** nav item live. Today `/games` is linked from the top nav
(`fragments/nav.html` line 16) but **no web route serves it** — only `/games/{id}` (detail)
exists. This work adds a scoreboard-style **games-by-date** page that:

1. Shows all games for a single date, grouped/sorted sensibly.
2. Lets the user move between dates (prev/next, date picker).
3. Links each game to its existing detail page (`/games/{id}`).
4. Opens on a **smart default date** based on the in-season / out-of-season rules below.

## 2. Scope & Non-Goals

In scope: one new `@Controller` (`GameWebController`), the date-defaulting logic, a
repository query for "games on an Eastern date," a page template + HTMX list fragment, CSS,
and tests.

Out of scope: the game detail page (already exists), live auto-refresh/polling of in-progress
scores, editing games, any REST API change.

## 3. Timezone model (important)

`Game.gameDate` is stored in **UTC**. Existing pages convert UTC → `America/New_York` for
display (e.g. `TeamWebController`, `ConferenceWebController`). A 9:00 PM ET tip is stored as the
next calendar day in UTC, so **"games on date D" must mean "games whose Eastern calendar date is
D."**

Implementation: for an Eastern date `D`, compute the half-open UTC window
`[D 00:00 America/New_York → UTC, (D+1) 00:00 America/New_York → UTC)` and query
`gameDate >= startUtc AND gameDate < endUtc`. All "today"/"noon" comparisons use
`America/New_York` (handles EST/EDT automatically; the user said "EST" and the season is Nov–Apr,
but using the zone is safer).

## 4. Routing & Controller

New `GameWebController` (`@Controller`).

| Method | Route | Returns | Purpose |
|--------|-------|---------|---------|
| GET | `/games` | `pages/games` | Full page; resolves the smart default date (or `?date=`) |
| GET | `/games?date=YYYY-MM-DD` | `pages/games` | Full page for an explicit date |
| GET | `/games/on/{date}` | `fragments/games-list :: games-list` | HTMX swap of just the games list for a date |

- `currentPage = "games"` on the full-page route.
- `/games/{id}` (detail) is unchanged and unaffected — `{id}` is numeric, `/games/on/{date}` is a
  distinct path, so there's no routing collision.
- Invalid `date` param → fall back to the smart default (no 404; a malformed date shouldn't break
  the page).

## 5. Smart default date (the core logic)

This is the part needing your sign-off. Restating your rules:

1. **In season, before noon ET** → previous day's games.
2. **In season, at/after noon ET** → current day's games.
3. **Out of season, in November** → the **first day of the season**.
4. **Out of season, any other month** → the **last day of the season**.

### Proposed algorithm

Let `now` = current instant in `America/New_York`; `today` = its date; `S` = the most recent
season (max year) in the DB. Define from S's actual games (converted to Eastern dates):

- `firstGameDate(S)` = earliest Eastern date with a game in S
- `lastGameDate(S)` = latest Eastern date with a game in S

Then:

```
if firstGameDate(S) <= today <= lastGameDate(S):       # in season
    default = (now.time < 12:00) ? today - 1 day : today
    # clamp so today-1 never precedes the opener
    default = max(default, firstGameDate(S))
else:                                                   # out of season
    default = (today.month == NOVEMBER) ? firstGameDate(S) : lastGameDate(S)
```

If there are no seasons/games at all → render an empty state with today's date.

### Why game-date bounds instead of `Season.startDate/endDate`

`Season.startDate/endDate` are nominal (Nov 1 / Apr 30) and don't reflect when games actually
start/stop. Using the real min/max game dates makes "first/last day of the season" land on actual
game days, and makes the November rule behave correctly: in early November before the opener,
`today < firstGameDate`, so we're "out of season" and show the opener — exactly rule 3. Once games
tip off, `today` falls in range and the noon rule takes over.

This is open question #1 — confirm you want game-date bounds rather than the nominal season dates.

## 6. Page design

Header:
- Title "Games" + the resolved date, formatted (e.g. "Saturday, March 14, 2026").
- **Date navigation:** ◀ Prev | a native `<input type="date">` picker | Next ▶, plus a "Today"
  shortcut. Prev/Next behavior is open question #2 (adjacent calendar day vs. adjacent day that
  has games).
- A small count ("42 games").

Games list (HTMX-swappable region):
- One **section per conference matchup type** is *not* proposed; instead a single list sorted by
  **tip-off time, then matchup**. (Grouping option is open question #3.)
- Each row mirrors the team page's `schedule-table` vocabulary: status/time, away team (logo +
  name, seed if any), `@`/vs/neutral, home team, score or scheduled time, result emphasis,
  OT label, tournament badge, conference-game marker. Whole row links to `/games/{id}`.
- Status handling:
  - `SCHEDULED` → show Eastern tip time (e.g. "7:00 PM") + spread/OU if present.
  - `IN_PROGRESS` → "LIVE" badge + current score.
  - `FINAL` → final score, winner emphasized, OT label.
  - `POSTPONED` / `CANCELLED` → labeled, de-emphasized.
- Empty state when a date has no games ("No games on this date.").

## 7. Data sources & queries

| Need | Source |
|------|--------|
| Games on an Eastern date | new `GameRepository.findByEasternDate(startUtc, endUtc)` with `JOIN FETCH home/away + LEFT JOIN FETCH bettingOdds`, ordered by `gameDate, id` |
| Most-recent season | `SeasonRepository.findTopByOrderByYearDesc()` |
| First/last game date of a season | new lightweight `GameRepository` min/max queries (UTC instants, converted to Eastern in Java), or derived from a single fetch |
| Prev/next date with games | new `GameRepository` "max gameDate < startUtc" / "min gameDate >= endUtc" queries (only if we go with "adjacent day with games") |
| Tournament badge | existing `TournamentBadgeFormatter` |

No schema changes; `idx_games_date` already covers the window query.

## 8. DTOs (controller-local records, mirroring existing controllers)

- `GameDayRow(gameId, easternTime, awayTeamId, awayName, awayLogo, awayAbbr, awaySeed, awayScore,
  homeTeamId, homeName, homeLogo, homeAbbr, homeSeed, homeScore, status, location, result,
  periods, spread, overUnder, conferenceGame, tournamentBadge)` — analogous to
  `TeamWebController.GameRow`.
- The page model carries: `date`, `prevDate`, `nextDate`, `todayDate`, `games` (list), `gameCount`.

## 9. Open questions

1. **Default-date basis:** Use each season's **actual first/last game dates** (recommended,
   robust) or the nominal **`Season.startDate`/`endDate`** (Nov 1 / Apr 30)? Default: actual game
   dates.

2. **Prev/Next semantics:** Jump to the **adjacent day that actually has games** (recommended —
   skips empty summer/off-days, never lands on a blank page) or strict **calendar ±1 day** (can
   land on empty dates)? The date picker always allows jumping to any specific day regardless.
   Default: adjacent day with games.

3. **Grouping within a day:** Single time-sorted list (recommended, simplest) or group by
   something (e.g. tournament/conference vs. non-conference, or "Top 25 / featured" first)? We have
   no "featured" concept today. Default: single time-sorted list.

4. **"Noon ET" boundary:** at exactly 12:00:00 ET, treat as afternoon (show today)? Default: yes,
   `>= 12:00` shows today; strictly before noon shows yesterday.

5. **Date display range guard:** should the date picker be bounded to the span of seasons we have
   data for, or unbounded (user can pick any date, possibly empty)? Default: unbounded, with a
   graceful empty state.

6. **Cross-season dates:** the resolved/default date is always interpreted globally (any game on
   that Eastern date, regardless of season). Since seasons don't overlap calendar-wise this is
   simple, but confirming we don't need a season selector on this page. Default: no season selector
   — the date fully determines what's shown.
