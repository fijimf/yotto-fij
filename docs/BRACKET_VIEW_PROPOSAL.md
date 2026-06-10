# NCAA Men's Tournament Bracket View — Proposal

Status: **implemented** (June 2026), per the decisions recorded in "Decisions needed" below:
D1 classic two-sided desktop bracket + M1 region tabs on mobile, logos in cells, spreads on
scheduled games, permanent nav item March–November, inline First Four chips, self-correcting
Final Four pairing.

Implementation landed as:

- `BracketService` / `BracketView` — slot-tree derivation (seed-order placement, feeder
  matching fallback, TBD placeholders, FF notes, region pairing) with pure unit tests in
  `BracketServiceTest` (no Spring context needed).
- `BracketWebController` — `GET /seasons/{year}/bracket` + `GET /bracket` redirect to the
  latest bracket-bearing season.
- `GameRepository.findBySeasonIdAndTournamentTypeWithDetails` + `findMaxSeasonYearByTournamentType`.
- `templates/pages/bracket.html` + `templates/fragments/bracket.html` — single markup tree;
  CSS-only responsive split (`:has()` + radio region tabs under 1024px).
- Bracket section appended to `main.css`; connectors via logical-property pseudo-elements so
  the right side mirrors with `direction: rtl` alone.
- Entry points: nav "Bracket" item (months 3–11) and a button on the season stats page header.

This was item #5 ("Bracket view") deferred from
[TOURNAMENT_UI_PROPOSAL.md](TOURNAMENT_UI_PROPOSAL.md), scoped per the answers recorded there:
men's NCAA tournament only, no conference-tournament brackets for now.

## What the data gives us (verified against the live DB)

Every NCAA tournament `games` row carries `tournament_round`, `tournament_region`,
`home_seed`, and `away_seed`. For 2026 (the only season currently loaded) all 67 games are
present with 100% seed coverage. Round labels are already normalized:

| Round | Region tagged? | Games |
|---|---|---|
| First Four | yes (region of the slot they feed) | 4 |
| 1st Round | yes | 32 |
| 2nd Round | yes | 16 |
| Sweet 16 | yes | 8 |
| Elite 8 | yes | 4 |
| Final Four | no | 2 |
| National Championship | no | 1 |

### The one real modeling problem: bracket position is not in the data

ESPN gives us *games*, not *slots*. A bracket is a binary tree of 63 slots; we have a flat
list of games with round + region + seeds. We must derive each game's vertical position:

- **1st Round:** trivially derived from seeds. The canonical bracket order within a region
  is fixed: `1v16, 8v9, 5v12, 4v13, 6v11, 3v14, 7v10, 2v15`. Sort the 8 first-round games
  by `indexOf(min(homeSeed, awaySeed))` in `[1, 8, 5, 4, 6, 3, 7, 2]`.
- **Later rounds:** link each game to its two feeder games by matching participants —
  a 2nd-round game's teams are winners of two adjacent 1st-round games. Walk up the tree.
- **First Four:** each FF game's winner appears in a 1st-round game; attach it as an
  annotation on that slot.
- **Partial tournaments (mid-March):** future games don't exist as rows yet. The view model
  must be slot-based, not game-based: 63 slots always render; slots without a game show
  "TBD" or the possible feeders ("Winner of 8/9").
- **Final Four pairing:** which two regions meet in each semifinal is derivable from the
  semifinal games themselves once they exist; before that it's ambiguous (see open
  question Q6).

This derivation lives in a new `BracketService` that produces a `BracketViewModel`
(4 regions × 4 rounds of slots, + Final Four + Championship + First Four annotations).
It is pure in-memory logic over one repository query (`findBySeasonAndTournamentType`),
so it's cheap and easily unit-tested.

---

## Layout proposals

### Desktop (laptop, ≥ ~1024px)

**Option D1 — Classic two-sided full bracket (recommended)**

The iconic format: two regions on the left reading left→right, two regions on the right
reading right→left, Final Four and Championship in the center column. ~11 columns total.

```
EAST            ──────┐                              ┌──────            SOUTH
1 UConn 91 ─┐         │                              │         ┌─ 1 Houston 78
16 Stetson  ├─ UConn ─┤                              ├─ Hou ───┤  16 Longwd
8 FAU      ─┤  75     │── UConn ─┐        ┌─ Hou ────│  62     ├─ 8 Nebraska
9 NW       ─┘         │   82     │  FINAL │          │         └─ 9 TexasAM
...                   │          ├─ FOUR ─┤          │                ...
                      │          │  CHAMP │          │
WEST            ──────┘          └────────┘          └──────         MIDWEST
```

- *Pros:* the format everyone knows; maximal information density; whole tournament in one
  screenful on a 13"+ laptop.
- *Cons:* each game cell is small (~150–170px wide at 1280px viewport), so cells must be
  terse (seed + short name + score). Needs `min-width: ~1100px` with horizontal scroll
  below that — which is fine, because below that breakpoint we switch to the mobile layout.

**Option D2 — Region-stacked**

Four single-region brackets (4 columns each, left→right) stacked vertically, then a Final
Four / Championship panel at the bottom. Roomier cells, but ~4 screens of vertical scroll
and you lose the at-a-glance whole-bracket view. Not recommended for desktop — it wastes
the width a laptop gives you — but it's essentially what mobile gets anyway, so it costs
nothing to keep as the narrow-viewport fallback.

### Mobile (< ~1024px)

A 63-game two-sided bracket is hopeless on a phone. Three workable strategies:

**Option M1 — Region tabs + single-region bracket (recommended)**

A segmented control (`East | West | South | Midwest | FF/Champ`) above a single-region
4-column bracket. One region is 16→8→4→2→1, which at 4 columns fits a phone in landscape
and needs only modest horizontal scroll (with CSS `scroll-snap`) in portrait. The fifth
tab shows Final Four + Championship as three larger cards. Tab switching is pure CSS/HTMX-free
(all four regions rendered server-side, toggled with `:checked` radio inputs or a few lines
of vanilla JS) — consistent with the no-framework rule.

- *Pros:* preserves the bracket *shape* (the thing a bracket view is for); dense but legible.
- *Cons:* no single-glance overview; 5 taps to see everything.

**Option M2 — Round-by-round horizontal swipe**

Full-width columns, one per round (all regions interleaved), swiped horizontally with
scroll-snap. Good for "what's on tonight" but the tree structure disappears — it degrades
into a grouped game list.

**Option M3 — Plain list grouped by round/region**

Cheapest, zero layout risk, but it isn't a bracket; we already have schedule rows that do
this. Mentioned only for completeness — recommend against.

**Recommendation: D1 + M1 from a single template.** One Thymeleaf template renders the
slot grid once; CSS grid `grid-template-areas` re-flows it: ≥1024px = two-sided full
bracket, <1024px = tabbed single-region. No duplicate markup, no JS reflow logic.

---

## Rendering technology

The original proposal sketch said "static SVG." Having looked at it, **HTML + CSS grid is
the better fit** and is what's proposed here:

| | HTML/CSS grid | SVG |
|---|---|---|
| Connector lines | pseudo-elements with `border` (well-trodden technique) | trivially precise |
| Clickable team/game links | native `<a>` | possible but clunky |
| Responsive re-flow | free via media queries | essentially a second layout |
| Text overflow/ellipsis | native | manual |
| Design tokens / theming | `var(--token)` everywhere | needs duplication |
| Accessibility | semantic, screen-reader-friendly | poor by default |

Connector lines via `::after` borders on each matchup pair are the only fiddly part; the
fallback if they fight us is simply to omit connectors and rely on column alignment +
alternating row tints, which many modern bracket UIs do anyway.

## Game cell anatomy (information density)

```
┌────────────────────┐
│ 1  UConn      75 ◂ │   ← winner: bold + left accent bar in --color-primary
│ 9  N'western  58   │   ← loser: --color-text-muted
└────────────────────┘
```

- Seed in a fixed-width muted slot; team short name (entity `abbreviation`, fall back to
  truncated name) linking to `/teams/{slug}`; score right-aligned in tabular figures.
- Whole cell links to `/games/{id}` (team name links take precedence via nested anchors —
  same pattern as schedule rows).
- **Scheduled games:** date + time in place of scores; optionally the spread (Q3).
- **TBD slots:** dashed border, "Winner of 8/9" in muted text.
- **First Four:** small annotation chip under the relevant 1st-round seed line
  (`16 ▸ FF: Stetson 71, Wagner 68`), or a separate strip below the bracket (Q5).
- Title row: "NCAA Tournament · 2026" + season switcher (reuses the existing season
  dropdown pattern) once more seasons are scraped.

Deliberately **excluded** from cells to protect density: logos (16px logos at this cell
size are noise — Q2 if you disagree), conference, records. Hover tooltip (`title` attr)
carries full team name + record at zero pixel cost.

## Routes & navigation

- Page: `GET /seasons/{year}/bracket` → `pages/bracket.html` (alongside the existing
  `/seasons/{year}/stats` convention). `BracketWebController` per naming convention.
- 404s cleanly (or renders an empty-state card) for seasons with no NCAA games.
- Entry points: a "Bracket" link on the season stats page header, and on home/games pages
  during the tournament window. A permanent top-nav item is Q4.

## Implementation plan & effort

| Step | What | Effort |
|---|---|---|
| 1 | `BracketService` + `BracketViewModel`: slot tree derivation from game rows (seed-order table, feeder matching, TBD slots) + unit tests against the 2026 data | M |
| 2 | `BracketWebController` + `pages/bracket.html` rendering the slot grid | S |
| 3 | Desktop CSS: two-sided grid + connectors, `main.css` | M |
| 4 | Mobile CSS: region tabs + 4-column region grid + scroll-snap | M |
| 5 | Polish: Final Four/Championship center panel, First Four chips, empty/partial states, entry-point links | S |

Total: roughly the size of the recent tournament-identity phase. Steps 1–2 are
independently shippable (an unstyled-but-correct bracket), 3–4 are where the visual risk
lives. No schema changes, no scraper changes, one new read-only query.

During the live tournament the page is static per request (no polling); the existing
12-hour re-scrape keeps it current. Live-updating cells via HTMX polling is a possible
phase 2, deliberately out of scope here.

---

## Decisions needed

**Q1 — Desktop layout:** D1 classic two-sided bracket (recommended) vs D2 region-stacked?
<br/>**Comment** -- D1

**Q2 — Cell contents:** seed + abbreviation + score only (recommended), or also team
logos? Logos add brand recognition but cost ~20px per row in cells that are already tight.
<br/>**Comment** -- Add logos

**Q3 — Betting lines on scheduled games:** show the spread on not-yet-played games
(data exists via `BettingOdds`)? Adds a row of genuinely useful info pre-game, but
clutters the cell. My lean: yes, but only on the mobile/region view where cells are wider.
**Comment** -- Show spread

**Q4 — Navigation:** permanent "Bracket" item in the top nav (visible year-round, mostly
linking to last year's bracket), or contextual links only (season page + home page during
March/April)? My lean: contextual only until there are multiple seasons of data.
<br/>**Comment** -- Permanent until March until November.  Historic will navigate by from seaseon

**Q5 — First Four presentation:** inline chip under the affected 1st-round slot
(recommended; keeps everything in one structure) vs a separate 4-game strip below the
bracket (cleaner cells, but visually orphaned)?

**Q6 — Final Four region pairing before semifinals exist:** when rendering a partial
bracket pre-Final-Four, the left/right region arrangement is arbitrary. Options: fixed
order (East/West left, South/Midwest right) until real semifinal games pin it down
(recommended — simple, self-correcting), or scrape the pairing from ESPN's bracket
endpoint (extra scraper work for a cosmetic detail).
<br/>**Comment** -- Your recommendation

**Q7 — Ambiguity worth confirming:** "information dense" — is the goal *the whole
tournament on one laptop screen* (drives D1 and the terse cells above), or *rich detail
per game* (would push toward D2 with bigger cells carrying odds, records, links)? This
proposal assumes the former.
<br/>**Comment** -- D1
