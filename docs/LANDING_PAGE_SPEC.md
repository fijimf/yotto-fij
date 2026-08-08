# Landing Page Redesign — Spec (Skeleton + Ideas)

Status: **DRAFT / skeleton for discussion** (2026-08-07)
Supersedes punchlist item 12 ("Better landing page") and folds in item 13 ("Personalization").

---

## 1. Goals

The current home page (`HomeController` → `pages/home.html`) is date-blind: brand hero,
four lifetime count tiles, six nav cards, news panel. It looks identical on the morning
of the national championship and on July 4th. The redesign makes the front page a
**phase-aware daily front door**: it should answer "what's happening in college
basketball *right now*, and what does DeepFij think about it" — and degrade gracefully
to something warm and alive even in the dead of summer.

Non-goals (for now): replacing the global nav (punchlist "Revised Menu" is a separate
effort), mobile app, push notifications.

---

## 2. The Phase Model

### 2.1 States

| # | Phase | Enters when | Exits when | One-line mood |
|---|-------|-------------|------------|---------------|
| 1 | `OFFSEASON` | ~2 weeks after title game | Oct 15 (or Season.startDate − 21d) | Museum / archive. Quiet confidence. |
| 2 | `PRESEASON` | Oct 15 → first scheduled game | First game tips | Anticipation. Countdown energy. |
| 3 | `IN_SEASON` | First game | Selection Sunday (or last regular-season/conf-tourney game) | The daily engine. Scores + predictions. |
| 4 | `POSTSEASON` | **Selection Sunday** (field set) | Championship game FINAL | Bracket takes over the page. |
| 5 | `EPILOGUE` | Championship FINAL | ~2 weeks later → `OFFSEASON` | Season wrap. Credits roll. |

Notes:
- `EPILOGUE` is deliberately short-lived and deliberately different from `OFFSEASON` —
  it's the "season in review" moment while people still care.
- **Championship Sunday overlap.** POSTSEASON begins on Selection Sunday, but several
  conference title games are *played that same day* (some tip before the reveal, some
  after). The phase flips, but the day-1 POSTSEASON composition must still carry M2/M3
  for that day's conference championship games — an auto-bid is bracket news, not
  regular-season residue. Rule: on Selection Sunday (and only then), the bracket panel
  and the live/finished conf-title games render side by side, with each title-game
  result annotated with the bid it clinched ("Champions — 12-seed, East"). See the
  flair flag in 2.3.
- Conference tournament week is *not* a separate state (it's `IN_SEASON` with a flair
  flag — see 2.3) to keep the state machine small. Revisit if it earns its own state.

### 2.2 `SeasonPhaseService` (new)

Central resolver; nothing else in the app has this today (GameWebController,
ConferenceWebController, BracketWebController, nav.html each re-derive "now" ad hoc —
nav.html literally month-checks for the bracket link).

```java
record SeasonPhase(Phase phase, Season season, LocalDate today,
                   LocalDate firstGameDate, LocalDate lastGameDate,
                   Optional<LocalDate> championshipDate,
                   boolean confTourneyWeek /* flair flag */) {}
```

Inputs (all already exist):
- `SeasonRepository.findByDate` / `findTopByOrderByYearDesc`
- `GameRepository.findMinGameDate` / `findMaxGameDate`
- tournamentType on games + `BracketService.latestBracketYear()` for POSTSEASON detection
- `EasternDates` for all "today" math (Eastern calendar date is canon)

Rules of engagement:
- **Data-driven, date-fallback.** Prefer real signals (a FINAL championship game) over
  calendar guesses (Apr 8). Fall back to fixed dates when data is missing.
- **Admin override.** `GET/POST /admin` gets a "force phase" dropdown (stored in a
  simple app-setting or just a property) so we can preview any state at any time.
  This doubles as the dev/QA story.
- Nav's bracket-link month hack gets replaced by this service (drive-by fix).

### 2.3 Flair flags (sub-states that tweak, not replace, a phase)

- `confTourneyWeek` — IN_SEASON + championship-week banner/ordering
- `selectionSunday` — day 1 of POSTSEASON; enables the reveal takeover (idea 6) AND
  keeps that day's conference championship games on the page (see 2.1 note)
- `openingNight` / `rivalryDay` candidates later

---

## 3. Module Inventory

Five modules; each defines behavior **per phase**. The page is a composition of these,
ordered per phase (section 4). All panels should be Thymeleaf fragments (several
already exist and are drop-in: `fragments/games-list`, `fragments/prediction-card`,
`fragments/news-cards`, `fragments/rankings-table`, `fragments/bracket`).

### 3.1 M1 — Message / Hero

Static or limited-rotation editorial voice at the top. Replaces the current count-tile
hero (counts can survive as a small footer strip — they're charming, just not the lede).

| Phase | Content |
|-------|---------|
| OFFSEASON | Rotating "from the archive" line + brand statement. Ex: "147 days until tip-off. Meanwhile: the 2019 shot chart you forgot about." |
| PRESEASON | **Countdown to first game** (real date from schedule, not hardcoded), plus a live-ticking countdown clock (days/hours/minutes/seconds to the opener's tip instant) split-screen beside the "Never-Too-Early top 10" (naming note: "way-too-early" is overused elsewhere). |
| IN_SEASON | One-line daily summary, generated from data: "63 games tonight. The model likes 4 upsets." Rotation of 2–3 variants max. |
| POSTSEASON | Bracket-centric: "Sweet 16 starts Thursday. Model survival odds inside." |
| EPILOGUE | "That's a wrap on 2025–26." + single biggest stat of the season. |

- Rotation mechanism: server-side pick from a small pool, seeded by date (stable per
  day, no client JS needed). The existing `randomQuote` advice is the precedent.
- Keep the quote banner; it's part of the site's personality.

### 3.2 M2 — Results

Yesterday's (or most recent day's) scores. Reuses `/games/on/{date}` fragment +
`GameWebController.resolveDefaultDate()` noon-Eastern logic.

| Phase | Behavior |
|-------|----------|
| OFFSEASON | **Hidden** (or replaced by "This day in season history" — see ideas). |
| PRESEASON | Hidden. |
| IN_SEASON | Last night's scores, capped (top N by interest — see "interestingness" idea), "all scores →" link. Model-vs-actual badges (✓ winner, ✓ ATS) inline via existing `PredictionCardView` flags. |
| POSTSEASON | Tourney results styled as bracket progress; each result links to updated bracket. **Selection Sunday exception:** that day's conference championship results/live games shown alongside the bracket, badged with the auto-bid clinched. |
| EPILOGUE | Final Four / championship result pinned for the duration. |

### 3.3 M3 — Upcoming Games / Predictions

The model's public face. Reuses `PredictionsPageService.build()` + prediction-card
fragment.

| Phase | Behavior |
|-------|----------|
| OFFSEASON | Hidden. |
| PRESEASON | **Preseason predictions**: model's projected top 25 / conference winners from priors (prior-v3/eff-v4 feature sets already encode preseason priors). "Bold calls" list. |
| IN_SEASON | Tonight's slate with predicted spreads/win probs; "Model's best bets" (largest model-vs-book disagreement — data already exists: pred spread vs book spread). |
| POSTSEASON | Round-by-round survival probabilities; "model's bracket" vs actual. |
| EPILOGUE | Hidden (its content moves into the wrap module). |

### 3.4 M4 — News

Already built (`NewsQueryService.frontPage()`), with a known offseason problem:
volume drops ~10× and the recency-decayed panel goes thin/stale (NEWS_MODULE.md §5.10).

| Phase | Behavior |
|-------|----------|
| OFFSEASON | **Promoted to primary content** (it's most of what exists). Widen the decay half-life or per-source caps seasonally so the panel stays full; coaching-carousel/transfer-portal stories dominate naturally. |
| PRESEASON | Prominent, second position. |
| IN_SEASON | Present but compact (4–6 cards); scores/predictions outrank it. |
| POSTSEASON | Compact; tourney stories will dominate organically. |
| EPILOGUE | Prominent again. |

- Resolves NEWS_MODULE.md's open question ("where does Latest News sit on the front
  page") with: *it depends on phase* — that's the whole thesis of this spec.
- Cleanup: the news panel in `home.html` uses inline styles; normalize to BEM in
  `main.css` during the rebuild.

### 3.5 M5 — Personalization (registered users)

Infrastructure exists (`UserPreference` K/V, `UserPreferenceService`, account page)
but only one key is defined — and that one (`email.daily-update`) currently has **no
producer**; the toggle is inert. This module gives it a reason to live.

New preference keys (follow `PreferenceKeys` dot-namespace convention):
- `favorite.team-ids` — CSV of team ids (VARCHAR(2000) is plenty)
- `favorite.conference-id` — optional single conference
- `home.compact` — dense vs comfortable layout (maybe later)

Where it shows up:
- **"Your Teams" strip** pinned above the fold when logged in: per favorite team —
  last result, next game + model line, live rank/rating, latest team-tagged news
  (`NewsQueryService.teamNews(teamId, limit)` already exists for exactly this).
- Follow buttons ("☆ Follow") on team pages, not just a settings form — set the
  preference where the intent happens.
- Phase behavior: in OFFSEASON the strip becomes "your teams' offseason" (news +
  roster stories only); in POSTSEASON it becomes "your teams' bracket path" (or a
  gentle "eliminated in R32 — here's who knocked you out and how far the model thinks
  *they* go").
- Anonymous users: consider a cookie-based single favorite team as a teaser →
  "register to follow more teams" conversion hook.
- **Daily-update email finally ships**: the same per-phase composition rendered as the
  daily digest for users with `email.daily-update` on. One composition engine, two
  outputs (page + email). This is the forcing function that makes the module design
  honest — if a panel can't render in an email, it's probably too clever.

---

## 4. Per-Phase Page Composition (module order, top to bottom)

```
OFFSEASON:   M1 hero (archive flavor) · M4 news (wide) · [idea: time-machine panel] · footer counts
PRESEASON:   M1 hero (countdown) · M3 preseason predictions · M4 news · M5 your-teams · footer
IN_SEASON:   M1 hero (daily line) · M5 your-teams · M2 results · M3 tonight's slate · M4 news (compact)
POSTSEASON:  M1 hero · bracket (full-width, from fragments/bracket) · M2 tourney results · M3 survival odds · M4 news
EPILOGUE:    M1 hero (wrap) · season-wrap module (see ideas) · M4 news · footer
```

Logged-out users see the same order minus M5 (with the follow-a-team teaser slot).

---

## 5. Ideas (the "no idea too crazy" section)

Roughly sorted from "should probably just do" to "genuinely unhinged".

1. **Interestingness score for games.** Rank last night's results and tonight's slate
   by a composite: model-vs-book disagreement, upset probability, ranking of teams,
   OT/margin drama (for results). The front page shows the top 6 *interesting* games,
   not the first 6 alphabetically. All inputs already in `PredictionCardView`.
2. **Model report card strip.** Tiny persistent in-season strip: "Yesterday: 41–12
   straight up, 29–24 ATS." Data is already in `prediction_evaluations`. Honesty as a
   brand feature — show it on bad days too.
3. **Countdown clock (PRESEASON)** to the real first scheduled game, with opponent
   names once the schedule is scraped. Cheap, high charm.
4. **"This day in season history" (OFFSEASON).** July 22 → show the best game played
   on any Jan/Feb/Mar day chosen by daily seed, from any archived season: final score,
   the model's pre-game line, what happened. The archive is the product in July.
   Twist: "the model's biggest miss on this date" — self-deprecating and fun.
5. **Season Wrapped (EPILOGUE).** Spotify-Wrapped-style recap: best team, most
   improbable win (lowest pre-game win prob that hit), model's best/worst call,
   conference of the year, biggest rating climb Nov→Apr. For logged-in users, a
   *personal* wrapped for their followed teams. This is the entire EPILOGUE state's
   reason to exist.
6. **Selection Sunday takeover (flair).** For ~24h the page leads with the bracket
   reveal as it's known, model's projected seeds vs actual, "biggest snub by rating" —
   with the day's live conference championship games pinned right beside it (they're
   deciding auto-bids in real time; "winner is dancing" framing writes itself).
7. **Model vs. Vegas ticker.** Season-long running ATS record vs closing line,
   displayed like a stock ticker. (Careful framing: analytics, not tout service.)
8. **Live "pulse" header dot.** Small HTMX-polled indicator during game windows:
   "17 games in progress" — reuses the scrape-status polling pattern from /admin.
9. **Upset alarm.** In-season module slot that appears only when the model gives a
   ranked team ≥35% chance of losing tonight: "Upset watch: 3 alarms tonight."
10. **Preseason "way-too-early" time capsule.** Store the model's preseason top 25;
    in EPILOGUE, grade it publicly. Ties PRESEASON and EPILOGUE together in a loop.
11. **Rivalry radar (personalized).** If a followed team's rival is on tonight's
    slate, the strip says so ("scoreboard-watching mode").
12. **Front page as poster (unhinged tier).** On championship morning, the entire
    landing page is a single full-bleed "gameday poster" — two logos, the model line,
    tip time. One day a year the site gets to be beautiful instead of dense.
13. **ASCII bracket easter egg.** `curl deepfij.com/bracket.txt` returns the live
    bracket in monospace. Zero UI cost, infinite nerd cred.
14. **Museum mode (deep OFFSEASON).** A rotating "exhibit": one archived season per
    week gets a curated front-page card ("The 2021 season: a retrospective"), linking
    to existing season/rankings pages filtered to that year. Content from data, no
    editorial labor.

---

## 6. Implementation Sketch

Detailed phased plan: [LANDING_PAGE_IMPLEMENTATION_PLAN.md](LANDING_PAGE_IMPLEMENTATION_PLAN.md).
High-level shape, each step shipping something visible:

1. **Phase 0 — `SeasonPhaseService`** + admin force-phase override + replace nav.html
   bracket month hack. Pure plumbing, unblocks everything.
2. **Phase 1 — IN_SEASON composition** (the hard/valuable one): rework
   `HomeController` to build per-phase model; M2 results + M3 slate panels from
   existing fragments; M1 daily hero; news made compact. Other phases temporarily
   fall back to something close to today's page.
3. **Phase 2 — PRESEASON + OFFSEASON** (countdown, news-forward layout, archive
   panel). These are cheap once composition exists.
4. **Phase 3 — Personalization**: `favorite.team-ids` key, follow buttons on team
   pages, "Your Teams" strip, anonymous cookie teaser.
5. **Phase 4 — POSTSEASON + EPILOGUE** (bracket takeover, wrapped module) — can ship
   any time before March.
6. **Phase 5 — Daily digest email** reusing the composition (finally activates
   `email.daily-update`).

Technical notes:
- `HomeController` gets a `HomeCompositionService` (or similar) that returns an
  ordered list of panel view-models; the template iterates. Keeps per-phase logic out
  of Thymeleaf.
- HTMX only where content is live (pulse dot, maybe scores after games go final);
  the base page is server-rendered and cacheable per (phase, date, user).
- Add a `HomeControllerTest` (none exists) — at minimum one render test per phase via
  the force-phase override.
- Watch the betting-odds sign convention (`betting_odds.spread` is handicap
  orientation; model spreads are home margins) in any new model-vs-book display —
  this has bitten twice before.

---

## 7. Open Questions

1. ~~Exact POSTSEASON entry~~ **Decided: Selection Sunday.** Same-day conference
   championship games stay on the page via the `selectionSunday` flair (2.1 note).
2. Does NIT/other postseason count as POSTSEASON, or is it NCAA-only with NIT as
   IN_SEASON residue?
   **NIT is noise.  Report game scores, but ignore otherwise.  As are Crown etc.**
3. EPILOGUE duration: fixed 14 days, or until news volume decays below a threshold?
   **14 days seems appropriate**
4. Should the count tiles (teams/games/seasons/conferences) survive at all, or move
   to an /about page?
   **An about page would be nice, but they were filler til we got better content**
5. Anonymous cookie favorite: worth the cookie-consent surface area, or registered-only?
   **Registered only, suggesting Duke or Carolina or Michigan or Kentucky will alienate most people**
6. Does conference-tourney week earn full-state status once we see it in practice?
   **It might, but a path isn't clear.  Put off for now.**
