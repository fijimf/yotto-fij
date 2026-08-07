# Landing Page Redesign — Implementation Plan

Companion to [LANDING_PAGE_SPEC.md](LANDING_PAGE_SPEC.md). Spec decisions baked in here:
POSTSEASON enters on Selection Sunday (same-day conf title games stay visible);
NIT/CBI/Crown are scores-only noise; EPILOGUE is a fixed 14 days; count tiles move to a
new `/about` page; personalization is registered-users-only (no anonymous cookie);
conference-tourney week stays a flair flag.

Six phases, each independently shippable and deployable. Phase N never depends on
Phase N+1. Suggested order is 0→1→2→3→4→5, but 3 (personalization) and 4
(postseason/epilogue) can swap freely; 4 just has to land before March.

---

## Phase 0 — `SeasonPhaseService` (plumbing; unblocks everything)

**Goal:** one canonical answer to "what phase is it, for which season, as of which
Eastern date" + an admin override to preview any phase. No visible page changes except
the nav bracket-link fix.

### 0.1 New: `service/SeasonPhaseService.java`

```java
public enum Phase { OFFSEASON, PRESEASON, IN_SEASON, POSTSEASON, EPILOGUE }

public record SeasonPhase(
    Phase phase,
    Season season,               // the season this phase refers to (see resolution below)
    LocalDate today,             // Eastern calendar date used for the decision
    LocalDate firstGameDate,     // min game date of season (null if no games yet)
    LocalDate lastGameDate,      // max game date of season
    LocalDate championshipDate,  // date of NCAA title game if known, else null
    boolean confTourneyWeek,     // flair: any CONFERENCE_TOURNAMENT game within ±3 days
    boolean selectionSunday      // flair: today == postseasonEntryDate
) {}

public SeasonPhase current();                    // uses EasternDates.toEasternDate(now)
public SeasonPhase asOf(LocalDate easternDate);  // testable core; current() delegates
```

Inject `java.time.Clock` (bean defaults to `Clock.systemUTC()`) so `current()` is
testable with a fixed clock — same pattern the codebase should converge on generally.

**Season resolution:** `SeasonRepository.findByDate(today)`; if none matches (summer
gap between seasons), `findTopByOrderByYearDesc()`. The "season" of an OFFSEASON
phase is the *most recently completed* season until PRESEASON flips it to the next
one (a Season row for the upcoming year may or may not exist yet — handle both).

**Phase decision, evaluated in order (data-driven, date-fallback):**

1. **EPILOGUE / OFFSEASON split.** Find the NCAA championship game: FINAL game with
   `tournamentType = NCAA_TOURNAMENT` and `tournamentRound` matching (case-insensitive)
   `championship`/`national final`; fallback = max-date FINAL NCAA_TOURNAMENT game of
   the season. If found and `today ≤ championshipDate + 14d` → `EPILOGUE`; if
   `today > championshipDate + 14d` → `OFFSEASON`.
2. **POSTSEASON.** Season has any `NCAA_TOURNAMENT` game (SCHEDULED or later) and the
   championship is not yet FINAL → `POSTSEASON`. Entry date = earliest scrape
   appearance isn't stored, so define `postseasonEntryDate` = the Sunday on or before
   `firstNcaaGameDate − 2d` (First Four is Tue/Wed; R64 Thu — the Sunday before First
   Four is Selection Sunday). `selectionSunday` flair = `today == postseasonEntryDate`.
   NIT/CBI/CROWN/OTHER_POSTSEASON games are **ignored by all phase logic**.
3. **IN_SEASON.** `firstGameDate ≤ today` and not postseason → `IN_SEASON`.
   `confTourneyWeek` = any CONFERENCE_TOURNAMENT game within `[today−1, today+5]`.
4. **PRESEASON.** `today ≥ min(Oct 15, firstGameDate − 21d)` of the upcoming season →
   `PRESEASON`. Works whether or not games are scraped yet (date fallback: Oct 15).
5. Otherwise → `OFFSEASON`.

**Edge cases to handle explicitly (and unit-test):**
- No seasons in DB at all (fresh install) → synthesize OFFSEASON with null season;
  home page must not NPE.
- Season row exists but zero games scraped (preseason before first scrape).
- `today` inside Season date range but before first game (early November gap).
- Championship game POSTPONED/CANCELLED (fall back to max-date NCAA FINAL).

### 0.2 New: force-phase admin override

- `SeasonPhaseService` holds a `volatile Phase forcedPhase` (null = real logic), plus
  optional `forcedDate` for previewing flairs. In-memory only — resets on restart,
  which is exactly right for a QA tool. Additionally honor property
  `app.home.force-phase` (dev profile convenience).
- `POST /admin/phase` (params `phase`, optional `date`, blank = clear) in
  `AdminController`; dropdown + current-phase readout added to the `/admin` dashboard
  template. Requires ADMIN like everything under `/admin/**`.
- When forced, `current()` returns the forced phase but computes all derived fields
  from real data where possible (forced POSTSEASON in July will have null bracket —
  panels must degrade, which is itself worth QA'ing).

### 0.3 Modified: `fragments/nav.html`

Replace the bracket-link month check with a model attribute. Add a
`@ControllerAdvice` (extend the existing `QuoteModelAdvice` pattern — either add to it
or a new `SeasonPhaseModelAdvice`) exposing `seasonPhase` globally; nav shows Bracket
link when `phase == POSTSEASON || phase == EPILOGUE` (or bracket data exists for the
latest season). **Perf note:** this advice runs on every page render — the phase
computation must be cheap. Cache the computed `SeasonPhase` for 10 minutes
(`@Cacheable` with a short TTL via Caffeine, or a simple timestamped volatile —
prefer the simple volatile; no new dependency).

### 0.4 Tests

- New `SeasonPhaseServiceTest` (integration, extends `BaseIntegrationTest`): seed
  minimal seasons/games per scenario, fixed clock, assert phase + flairs for ~12
  dates (mid-July, Oct 20, Nov 3, opening night, mid-Jan, conf-tourney Tue, Selection
  Sunday, First Four Tue, title game night, +7d, +15d, fresh-install empty DB).
  Follow the FK-safe `@BeforeEach` cleanup order (see memory note).
- Nav rendering test: bracket link present/absent under forced phases.

**Exit criteria:** all pages render unchanged; `/admin` can force any phase; nav
bracket link is data-driven; test suite green.
**Size:** ~2 new classes, 3 modified files, 1 test class. Small.

---

## Phase 1 — Composition engine + IN_SEASON page + `/about`

**Goal:** the real front page for the state we're in most of the year. Other phases
temporarily render a fallback close to today's page.

### 1.1 New: `service/HomePageService.java` (composition engine)

```java
public record HomePanel(String fragment, Map<String, Object> model) {}
public record HomePage(SeasonPhase phase, HeroView hero, List<HomePanel> panels) {}

public HomePage build(LocalDate date, @Nullable User user);
```

- One `build` method switches on phase and assembles the ordered panel list per spec
  §4. Panel fragments live in `templates/fragments/home/` (new subdirectory) and are
  rendered by the page template via dynamic include:
  `th:insert="~{fragments/home/__${panel.fragment}__ :: panel(${panel.model})}"`.
- Each panel builder is a private method returning `Optional<HomePanel>` — empty means
  "nothing to show, skip the slot" (e.g., no games yesterday). **The page must never
  render an empty panel shell.**
- This service is deliberately the *only* place that knows panel ordering. The email
  digest (Phase 5) reuses it.

### 1.2 Rework: `HomeController` + `pages/home.html`

- Controller shrinks to: resolve date (reuse the noon-Eastern rule — **extract
  `resolveDefaultDate()` from `GameWebController` into a shared
  `service/DisplayDateResolver`** rather than duplicating it), call
  `homePageService.build(...)`, put `homePage` on the model.
- `pages/home.html` becomes: hero fragment + panel loop + footer. Delete the count
  tiles and the six nav cards (nav bar already covers those destinations; keep at most
  a slim "explore" link row in the footer).
- Normalize all styles to BEM classes in `main.css` (`.home-panel`, `.home-panel__*`);
  remove the news panel's inline styles; remove `.home-nav__item:nth-child(3n)` grid
  hack. Panels stack single-column on mobile, 2-col ≥ 900px where a panel pair fits.

### 1.3 IN_SEASON panels (fragments in `templates/fragments/home/`)

| Panel fragment | Content | Source |
|---|---|---|
| `hero-inseason` | Data-generated one-liner: "N games tonight. The model likes K upsets." K = tonight's games where model win-prob favors the team the book doesn't, or favorite win-prob < 65%. 2–3 phrasing variants picked by date-seeded rotation (same trick as `QuoteService`). | `PredictionsPageService` output, counted in `HomePageService` |
| `results` | Yesterday's top-6 games by **interestingness v1** (see below), each row: teams/scores + ✓W / ✓ATS model badges; "All scores →" `/games`. | `PredictionsPageService.build()` `resultsByDate` (already merges scores + predictions + book lines — richer than `games-list`) |
| `slate` | Tonight's top-6 by interestingness: teams, tip time, model spread + win prob, book spread. "All predictions →" `/predictions`. | same, `upcomingByDate` |
| `report-card` | Slim strip: "Yesterday: 41–12 SU · 29–24 ATS" for the default model. Hidden if < 5 evaluated games yesterday. | new small query on `prediction_evaluations` (add to `PredictionEvaluationService`: `dailyRecord(modelKey, date)`) |
| `news-compact` | 4–6 cards, existing `news-cards` fragment restyled compact. | `NewsQueryService.frontPage()` |

**Interestingness v1** (utility class `HomeInterestScore`, unit-tested, all inputs
already on `PredictionCardView`):
- upcoming: `2.0·|modelSpread − (−bookSpread)| + 4.0·(1 − |winProb − 0.5|·2) + rankedBonus`
- results: same base + `3.0` if winner had pre-game win-prob < 0.35 (upset) + OT bonus
  (`periods > 2`).
- **Sign convention guardrail:** `betting_odds.spread` is handicap orientation
  (negative = home favored); model spreads are home margins. Convert with `−spread`
  before differencing — this exact mistake has shipped twice. Put the conversion in
  ONE place (`HomeInterestScore` takes already-normalized margins; the panel builder
  normalizes) and unit-test it with a real-world example in the test name.

### 1.4 Temporary fallbacks for other phases

`HomePageService` returns for OFFSEASON/PRESEASON/POSTSEASON/EPILOGUE: generic hero
(brand statement) + full `news-cards` panel + footer. This is roughly today's page,
so nothing regresses while Phases 2/4 are pending.

### 1.5 New: `/about` page

- `GET /about` (add to `HomeController`), `pages/about.html`: project blurb, the four
  count tiles (they move here, not deleted), data-source attribution (ESPN public
  APIs), model overview links (`/predictions/performance`). Footer link site-wide.

### 1.6 Tests

- `HomePageServiceTest` (integration): seed one day of games with odds + evaluations;
  assert IN_SEASON panel list order, interestingness top-6 selection, empty-slate day
  produces no `slate` panel.
- `HomeInterestScoreTest` (pure unit): ordering cases + the sign-convention case.
- `HomeControllerTest` (new — none exists): renders 200 + expected fragments for
  IN_SEASON (forced) and one fallback phase; `/about` renders counts.

**Exit criteria:** in-season front page shows hero/results/slate/report-card/news from
live data; other phases show the fallback; `/about` live; no inline styles left in
`home.html`.
**Size:** the biggest phase. ~4 new classes, ~6 new fragments, heavy template/CSS
work, 3 test classes.

---

## Phase 2 — PRESEASON + OFFSEASON compositions

**Goal:** the two quiet states. Cheap once the engine exists; ship before Oct 15.

### 2.1 PRESEASON panels

| Panel | Content | Source / notes |
|---|---|---|
| `hero-preseason` | Countdown: "Tip-off in 62 days — Nov 3" (real `firstGameDate`; if schedule not yet scraped, "The season returns in November"). | `SeasonPhase.firstGameDate` |
| `preseason-rankings` | "Way-too-early top 10": latest power ratings. v1 = final ratings of last completed season, labeled honestly ("where last season left off"); upgrade to model priors when preseason priors are queryable standalone. | `rankings-table` fragment, top-10 slice |
| `news` | Full-width prominent. | existing fragment |
| `schedule-teaser` | "Opening night: N games — headliners: X vs Y" once games exist; else omitted (Optional-empty). | `GameRepository.findScheduledBetween(firstGameDate, firstGameDate)` |

### 2.2 OFFSEASON panels

| Panel | Content | Source / notes |
|---|---|---|
| `hero-offseason` | "N days until tip-off" (next season known) or archive brand line. Date-seeded rotation of 3–4 lines. | |
| `news-wide` | Primary content. **Seasonal ranking config:** make `NewsProperties.ranking.halfLifeHours` effectively phase-aware — simplest: `NewsQueryService.frontPage(int count, double halfLifeHours)` overload; `HomePageService` passes 3× half-life + higher count in OFFSEASON. No config-file surgery. | NEWS_MODULE.md §5.10 staleness issue |
| `this-day-in-history` | Idea #4: best archived game played on today's month/day (any season), by interestingness-for-results; card shows score, model's pre-game line, one-line framing ("the model's biggest miss on this date" variant on Sundays). Needs `GameRepository.findFinalGamesOnMonthDay(month, day)` (new query, index-friendly: `WHERE EXTRACT(MONTH ...)` — acceptable at this volume, or filter in Java from a per-day cache). Omit panel gracefully if archive thin. | |
| footer counts stay on `/about` only | | |

### 2.3 Tests

Extend `HomePageServiceTest`: forced PRESEASON with/without scraped schedule
(countdown vs fallback text); forced OFFSEASON asserts wide news + history panel;
history query unit-tested for Feb 29 (return Feb 28/Mar 1 content, don't crash).

**Exit criteria:** all five phases have real compositions except POSTSEASON/EPILOGUE
(still fallback); force-phase preview looks right for OFFSEASON/PRESEASON.
**Size:** ~4 fragments, 1–2 repo queries, modest service work.

---

## Phase 3 — Personalization (registered users only)

**Goal:** follow teams; "Your Teams" strip on the front page. No anonymous cookies —
registered-only per spec decision.

### 3.1 Preference plumbing

- `PreferenceKeys`: add `FAVORITE_TEAM_IDS = "favorite.team-ids"` (CSV of team ids,
  hard cap **10** teams — enforce in service, not just UI).
- New `service/FavoriteTeamService`: `getFavorites(User) → List<Team>` (preserving
  user order), `follow(User, teamId)`, `unfollow(User, teamId)`; validates team
  exists, dedupes, enforces cap. Thin wrapper over `UserPreferenceService` — no
  schema change, no migration.

### 3.2 Follow UI

- Team page (`TeamWebController` / team template): ☆ Follow / ★ Following button for
  authenticated users, HTMX `POST /teams/{id}/follow` / `DELETE` toggling in place
  (returns the button fragment). Anonymous users see nothing (not a disabled button —
  no nagging).
- Account page: "Followed teams" list with remove buttons (server-rendered, no JS
  needed beyond HTMX), reusing the same service.
- CSRF: HTMX posts need the token header — follow the existing pattern used by
  account forms (there's a known MultipartFilter+CSRF gotcha in this codebase; these
  are plain posts, but copy the working HTMX+CSRF setup from admin templates).

### 3.3 `your-teams` panel (all phases where M5 appears)

Per followed team, one compact row; content varies by phase (all sources exist):
- IN_SEASON: last result (`GameRepository.findRecentFinalGamesForTeam`), next game +
  model line (via `PredictionsPageService` or direct query + `PredictionService`),
  current Massey rank.
- PRESEASON: first scheduled game + countdown; latest team news
  (`NewsQueryService.teamNews(teamId, 2)`).
- OFFSEASON: team news only.
- POSTSEASON: bracket status — alive (next game + survival line) or "eliminated by X
  in R32" (from NCAA_TOURNAMENT games involving the team).
- Panel position: per spec §4 (above results in IN_SEASON). Logged-out: a single
  quiet one-line teaser slot "Create an account to follow your teams" — text link,
  no team suggestions (decision: suggesting Duke/UNC/UK alienates everyone else).

### 3.4 Tests

`FavoriteTeamServiceTest` (cap, dedupe, unknown team → `IllegalArgumentException`);
controller test for follow/unfollow auth rules (anonymous → 401/redirect);
`HomePageServiceTest` case: user with 2 favorites gets `your-teams` panel with 2 rows,
user with 0 gets no panel (not an empty shell).

**Exit criteria:** follow/unfollow from team pages; strip renders per phase; account
page manages the list.
**Size:** 1 service, 2 controller touchpoints, 2 fragments, 1 pref key. Medium-small.

---

## Phase 4 — POSTSEASON + EPILOGUE (must land before March)

### 4.1 POSTSEASON composition

| Panel | Content | Source / notes |
|---|---|---|
| `hero-postseason` | Round-aware line: "Sweet 16 starts Thursday." Round derived from next SCHEDULED NCAA_TOURNAMENT game's `tournamentRound`. | |
| `bracket-embed` | Full-width `fragments/bracket` for `SeasonPhase.season`. Degrade: if bracket data missing (e.g. forced phase in QA), omit. | `BracketService.buildBracket(year)` |
| `tourney-results` | Most recent day's NCAA_TOURNAMENT results with seeds ("(3) Foo 71, (14) Bar 60"); winner links to bracket anchor. **NIT/CBI/Crown policy:** their FINAL scores appear in a collapsed "Other postseason scores" list below — scores reported, nothing else (no predictions, no badges, no news slots). | `homeSeed`/`awaySeed` already on Game |
| `survival` | v1: tonight's tourney games with model spread/win prob (exactly the slate panel filtered to NCAA_TOURNAMENT). v2 (stretch, separate PR): true round-by-round survival via bracket walk multiplying win probs — new `TournamentOddsService`, unit-tested against a hand-computed 4-team bracket. | |
| `news-compact`, `your-teams` (bracket-path mode, Phase 3) | | |

**Selection Sunday flair** (`selectionSunday == true`): insert `conf-champ-day` panel
directly beside/below the bracket: today's CONFERENCE_TOURNAMENT games where
`tournamentRound` matches (case-insensitive) `final|championship`; FINAL ones badged
"Champions — clinched auto-bid" (+ seed once NCAA seeds are scraped, matching team →
`homeSeed`/`awaySeed` in their first-round game). Live/scheduled ones show tip time +
model line. This panel appears **only** on that date.

### 4.2 EPILOGUE composition (14 days after championship FINAL)

| Panel | Content | Source / notes |
|---|---|---|
| `hero-epilogue` | "That's a wrap on {season}." + one headline stat (see wrap service). | |
| `season-wrap` | Wrapped v1, all computable from existing tables — champion + path; most improbable win (min pre-game `winProb` among winners, from `prediction_evaluations`); model's best call (largest correct upset) and worst miss (largest ATS error); biggest Nov→Apr Massey climb (`TeamPowerRatingSnapshot` first vs last); conference of the year (`ConferenceRankingService.aggregateBySeason`). New `service/SeasonWrapService` returning a `SeasonWrap` record; computed once and cached in-memory for the 14 days (recompute on restart is fine — cheap queries). Personal wrapped for followed teams = stretch, separate PR. | |
| `championship-result` | Pinned title-game result. | |
| `news` | Prominent. | |

### 4.3 Tests

Seed a miniature tournament (First Four → title) in `HomePageServiceTest`:
- POSTSEASON composition renders bracket + results with seeds; NIT game appears only
  in the collapsed list; Selection Sunday flair day inserts `conf-champ-day` and
  other days don't.
- EPILOGUE: day championship+1 → EPILOGUE with wrap panel; day +15 → OFFSEASON.
- `SeasonWrapServiceTest`: hand-seeded season, assert each superlative picks the
  right game/team.

**Exit criteria:** full March→April lifecycle previewable via force-phase (OFFSEASON
→ … → POSTSEASON w/ Selection Sunday flair → EPILOGUE → OFFSEASON) without errors on
partial data.
**Size:** ~5 fragments, 1–2 services, the richest test seeding. Medium-large.

---

## Phase 5 — Daily digest email (activates `email.daily-update`)

**Goal:** the inert account-page toggle finally does something: a morning email that
is the front page in miniature. Reuses `HomePageService` — if a panel can't render in
email, simplify the panel.

### 5.1 Rendering

- New Thymeleaf email template `templates/email/daily-digest.html`: inline-styles-only
  (email clients), linear single column, max ~3 sections: personalized "your teams"
  block (if any favorites), top results/slate (phase-appropriate, top 3–4 rows,
  text-only — no thumbnails), 3 news headlines as plain links. Footer: manage
  preferences link (`APP_BASE_URL` + `/account` — **never request-derived URLs**, per
  the existing token-link invariant) — this is the unsubscribe path.
- `HomePageService` gains `buildDigest(LocalDate, User) → DigestView` — a pared
  mapping of the same panel data (don't reuse HTML fragments; email needs its own
  markup, but it must not need its own *queries*).

### 5.2 Sending

- New `DailyDigestJob` (`@Scheduled`, default 12:30 UTC ≈ post-morning-scrape; cron
  property `app.digest.cron`, enabled flag `app.digest.enabled` default **false**,
  mirroring `news.enabled` convention).
- Recipients: enabled+verified users with pref `email.daily-update=true`
  (`UserPreferenceService` — add a `findUserIdsWithPreference(key, value)` repo query
  to avoid N+1 over all users).
- Send via existing `MailService` async path; new `MailKind.DAILY_DIGEST`. Batch with
  per-send try/catch — one bad address must not abort the run; log a summary count.
  Skip phases with nothing to say: OFFSEASON digests only send if there's ≥1 news
  item or a followed-team story (don't email "nothing happened").
- Record last-sent date per run in logs only (v1); idempotence guard: job checks it
  hasn't already run for `today` (simple table `daily_digest_runs(date pk)` —
  **migration V30** — cheap insurance against double-send on restart).

### 5.3 Tests

`DailyDigestJobTest`: seed 2 opted-in users (one with favorites), mail captured via
the logging mail service used in tests; assert per-user content differs, opt-out user
untouched, second run same day no-ops. Template smoke test: digest renders for each
phase without exception.

**Exit criteria:** flag on in prod → morning email matching the front page; account
toggle round-trips; no digest on empty days.
**Size:** 1 job, 1 template, 1 tiny migration, service additions. Medium.

---

## Cross-cutting

- **Docs:** each phase updates CLAUDE.md (one-line entries: SeasonPhaseService, new
  admin endpoint, digest job) and strikes through punchlist items 12/13 at the end.
  ADMIN_MANUAL untouched (ML-only).
- **Deploy:** no infra changes; standard `deploy.sh` per phase. Digest phase needs
  `app.digest.enabled=true` added to server `.env`/config when ready (server-ops
  skill covers file locations). Management port invariant unaffected.
- **Feature safety:** every panel builder returns `Optional.empty()` on missing data;
  the page renders with whatever panels exist. Forced-phase QA on partial data is the
  regression net for this.
- **Performance:** front page is the highest-traffic route. Budget: ≤ ~8 queries per
  anonymous render. `SeasonPhase` cached ~10 min; consider caching the anonymous
  IN_SEASON panel set per (date, hour) if p95 misbehaves — measure via Netdata before
  optimizing.
- **Ideas deferred** (not in any phase, tracked in spec §5): pulse dot (#8), upset
  alarm (#9), preseason time capsule (#10), rivalry radar (#11), gameday poster
  (#12), `bracket.txt` (#13), museum mode (#14), model-vs-Vegas ticker (#7).
  Time capsule (#10) is worth doing next preseason — requires only persisting a
  ratings snapshot labeled "preseason", revisit at Phase 2 ship time.

## Suggested sequencing vs. calendar (today: Aug 7, 2026)

| Phase | Target | Why |
|---|---|---|
| 0 | now | pure plumbing, small |
| 1 | Aug–Sep | biggest lift; in fallback it improves the page immediately |
| 2 | before Oct 15 | PRESEASON goes live automatically on that date |
| 3 | Oct–Nov | follow buttons most valuable as season starts |
| 4 | Dec–Feb (hard stop: early March) | must precede Selection Sunday |
| 5 | any time after 3 | most valuable in-season |
