# News Module — Design Document

Status: IMPLEMENTED (v1, 2026-07-18) — open-question answers are inline below;
deviations from the original draft are listed in "Implementation notes" at the end.
Author: sketched with Claude, 2026-07-17
Prior discussion: language/architecture debate resolved in favor of an in-app Java module
(see "Architecture placement" for the reasoning).

## 1. Overview

Add a news aggregation module that, on a schedule:

1. Polls a curated set of RSS/Atom feeds (and, later, a small number of scraped
   HTML index pages) for college-basketball news items.
2. For each new item, fetches the article page, extracts metadata (title,
   subtitle/description, image, publish date) and clean body text.
3. Deduplicates — both exact (canonical URL) and near-duplicate (wire stories
   republished across many outlets, via SimHash).
4. Tags each article with teams and conferences using a dictionary matcher
   built from the existing `teams`/`conferences` tables plus a curated alias
   table. No ML.
5. Scores each article from source authority + tag confidence; recency decay is
   applied at read time.
6. Serves the results as: a front-page panel, a `/news` page, and per-team /
   per-conference news sections.

**What we store:** link, title, subtitle/description (short), image URL,
publish date, source, hashes, tags. **What we never persist:** full article
body text. Body text is extracted transiently in memory for tagging and
SimHash, then discarded. This is the safe aggregation posture (link + title +
snippet + our own metadata) and it is a hard rule, not an optimization choice.

### Non-goals (v1)

- No full-text search over article bodies (we don't keep them).
- No ML topic classification (transfer portal, bracketology, etc.) — these can
  arrive later as keyword rules over title+snippet.
- No player-level tagging (no player gazetteer in the DB today).
- No comment/reaction features.
- No push notifications.
- No paywall circumvention of any kind: if a page blocks us, we keep the feed
  metadata only or skip it.

## 2. Architecture placement

**In-app module, not a separate service.** The app already runs all scraping
in-process (`scraping/` package) with mature machinery this module reuses
directly:

- `ScrapeBatch` for run tracking and admin visibility (add `NEWS` to
  `ScrapeBatch.ScrapeType`).
- `AsyncScrapeService`-style `@Async` wrappers for admin-triggered runs.
- A `@Scheduled` poller alongside `ScrapeScheduler` (separate cron — news
  cadence is ~30 min, game scraping is 12 h; news polling is **not** a step in
  `ScrapeOrchestrator`'s pipeline).
- Flyway owns the schema (`validate` mode) — news tables are ordinary
  migrations in the same schema, prefixed `news_`.
- Existing test conventions: Testcontainers + `@MockBean` on the HTTP client.

New code lives in:

```
com.yotto.basketball.news/
    NewsHttpClient.java        // RestClient wrapper: rate limit, UA, conditional GET, redirects
    FeedPoller.java            // Rome-based feed parse, per-source
    ArticleFetcher.java        // page fetch + jsoup extraction (og: tags, body text)
    UrlCanonicalizer.java      // static, heavily unit-tested
    SimHasher.java             // 64-bit simhash + hamming distance
    NewsDeduplicator.java      // URL + simhash clustering
    TeamTagger.java            // Aho-Corasick gazetteer matcher
    ConferenceResolver.java    // team -> season-scoped conference
    NewsScrapeService.java     // orchestrates one polling run, owns the ScrapeBatch
    NewsScheduler.java         // @Scheduled cron
    NewsQueryService.java      // read-side: scored/filtered article queries
```

Entities in `entity/`, repositories in `repository/`, admin controller
`AdminNewsController` (sibling of `AdminBroadcastController` etc.), public
pages via `HomeController` / `TeamWebController` / `ConferenceWebController`
additions plus a new `NewsWebController` for `/news`.

**Libraries** (all plain Maven deps, no new infrastructure):

| Concern | Library |
|---|---|
| Feed parsing | `com.rometools:rome` |
| HTML fetch/parse/meta | `jsoup` (already familiar pattern; check if already a dep) |
| Aho-Corasick matching | `org.ahocorasick:ahocorasick` (Robert Bor) |
| SimHash | hand-rolled (~60 lines, unit-tested) |

## 3. Data model (Flyway `V29__news_module.sql`)

### 3.1 `news_sources`

| column | type | notes |
|---|---|---|
| id | bigserial PK | |
| name | text NOT NULL | "ESPN Men's College Basketball" |
| domain | text NOT NULL | `espn.com` — used for URL→source attribution of *discovered* links too |
| feed_url | text UNIQUE | null for future HTML-index sources |
| source_type | varchar(16) NOT NULL | `RSS` (v1); `HTML_INDEX` reserved |
| authority_weight | int NOT NULL DEFAULT 50 | 0–100, hand-set |
| dedicated_cbb | boolean NOT NULL DEFAULT false | true = every item is college basketball; false = items must pass the sport filter (§5.4) |
| active | boolean NOT NULL DEFAULT true | admin toggle |
| auto_disabled_at | timestamp | set by health check, distinct from admin `active` |
| consecutive_failures | int NOT NULL DEFAULT 0 | |
| last_polled_at | timestamp | |
| last_success_at | timestamp | |
| etag | text | conditional GET state |
| last_modified_header | text | conditional GET state |
| notes | text | admin free text |

### 3.2 `news_articles`

| column | type | notes |
|---|---|---|
| id | bigserial PK | |
| url_canonical | text NOT NULL UNIQUE | post-canonicalization (§5.2) |
| url_original | text NOT NULL | as discovered, for debugging |
| source_id | FK news_sources | nullable — Google News sweep can surface domains with no `news_sources` row; see §5.1 |
| discovered_via_source_id | FK news_sources | the feed that surfaced it (differs from source_id for aggregator feeds) |
| title | text NOT NULL | |
| subtitle | text | og:description / feed summary, truncated to ~300 chars |
| image_url | text | og:image, absolutized |
| published_at | timestamp NOT NULL | see §5.3 for fallback rules |
| fetched_at | timestamp NOT NULL | |
| simhash | bigint | null when body too short to hash reliably (§5.5) |
| body_token_count | int | how much text the simhash saw; 0 = metadata-only item |
| duplicate_of_article_id | FK news_articles, nullable | non-null ⇒ suppressed from all listings; points at cluster representative. Self-reference instead of a separate cluster table — no circular FK |
| static_score | numeric NOT NULL | authority + tag-confidence component; recency applied at read time |
| tag_status | varchar(16) NOT NULL | `TAGGED`, `UNTAGGED` (matcher found nothing), `MANUAL` (admin-edited; retag runs must not overwrite) |

Indexes: `(published_at DESC)` partial on `duplicate_of_article_id IS NULL`;
`(source_id, published_at)`; `(simhash)` is *not* indexed — dedup scans a
bounded recent window (§5.5).

### 3.3 `news_article_teams` / `news_article_conferences`

| column | type | notes |
|---|---|---|
| article_id | FK, part of PK | ON DELETE CASCADE |
| team_id / conference_id | FK, part of PK | |
| confidence | numeric NOT NULL | 0–1, from the matcher |
| matched_via | text | alias text that matched, for admin debugging ("Tar Heels") |
| manual | boolean NOT NULL DEFAULT false | admin-added/confirmed; retag never removes manual rows |

### 3.4 `news_team_aliases`

The curated overlay on top of the auto-generated gazetteer (§5.6).

| column | type | notes |
|---|---|---|
| id | bigserial PK | |
| alias | text NOT NULL | matched case-insensitively unless `case_sensitive` |
| team_id | FK teams NOT NULL | |
| kind | varchar(16) NOT NULL | `AUTO` (seeded, regenerable), `MANUAL`, `BLOCK` |
| ambiguous | boolean NOT NULL DEFAULT false | §5.7 — never tags on its own |
| case_sensitive | boolean NOT NULL DEFAULT false | forced true for aliases ≤4 chars |
| enabled | boolean NOT NULL DEFAULT true | |
| created_by | text | 'system' or admin username |

Unique on `(alias, team_id)`. `BLOCK` kind means "this auto-seeded alias is
poison for this team — never match it" (e.g. drop the bare mascot "Tigers").

## 4. Pipeline

One polling run (`NewsScrapeService.pollAll()`), wrapped in a `ScrapeBatch`
of type `NEWS` (`seasonYear` = current season year by the same convention the
scheduler uses; news isn't really season-scoped, noted in Open Questions):

```
for each active, not-auto-disabled source (feed):
    conditional GET (ETag/Last-Modified) -> 304? skip
    parse feed (Rome)
    for each entry, newest first, cap N=50 per poll:
        canonicalize URL                     (§5.2)
        already have url_canonical? -> skip (cheap existence check, no fetch)
        fetch page (rate-limited, §5.9)      -> on failure: keep feed metadata only
        extract og: meta + body text (jsoup)
        resolve published_at                 (§5.3)
        sport filter if !dedicated_cbb       (§5.4) -> fail = discard silently (counted in batch)
        simhash + dedup scan                 (§5.5)
        tag teams + conferences              (§5.6–5.8)
        compute static_score, insert row + tag rows (single transaction per article)
    per-source failures increment consecutive_failures; success resets it
batch.complete()   // PARTIAL if some sources failed
```

Per-source errors never abort the run (same posture as per-date game-scrape
errors). An article-level failure (fetch died, parse exploded) is caught,
logged, counted, and the loop continues.

## 5. Detailed design & edge cases

### 5.1 Source attribution vs discovery

An aggregator feed (Google News RSS query) surfaces articles from arbitrary
domains. Attribution is by **final domain after canonicalization**, not by the
feed: look up `news_sources` by domain; if found, that's `source_id` (and its
`authority_weight` applies); if not, `source_id` is null and a configurable
default weight (`news.default-authority-weight=30`) applies.
`discovered_via_source_id` records the feed for debugging and admin review.
The admin "unknown domains" view (§7.2) turns recurring null-source domains
into curated sources over time.

### 5.2 URL canonicalization (`UrlCanonicalizer`)

This is where dedup lives or dies. Rules, in order:

1. **Follow redirects to the final URL** (bounded: max 5 hops, same rate
   limiter). This resolves Google News wrappers
   (`news.google.com/rss/articles/...`), feedburner links, and `t.co`-style
   shorteners. The *final* URL is what gets canonicalized. Redirect loop or
   >5 hops → treat as fetch failure.
2. Force `https`, lowercase scheme+host, strip `www.`, strip default ports.
3. Strip fragment.
4. Strip known tracking params (allowlist approach is safer inverted: strip
   `utm_*`, `fbclid`, `gclid`, `ref`, `src`, `partner`, `cmpid`, `ex_cid`);
   **keep** unknown params — some CMSes use `?id=12345` as the real key.
5. Sort remaining query params for stable ordering.
6. Strip trailing slash (except bare root).
7. **AMP/mobile variants:** rewrite `/amp/` path segments and `amp.` / `m.`
   host prefixes to the plain form *only* when the plain form is what the
   redirect chain lands on naturally; otherwise prefer the page's own
   `<link rel="canonical">` when present — if the page declares a canonical
   URL, canonicalize *that* and use it. (`rel="canonical"` is the single
   highest-value signal here; many syndicated pages point it at the original
   wire story, which upgrades our dedup for free.)

Edge cases handled explicitly:

- **Same URL, changed content** (title edited, "UPDATED" prepended): we key on
  URL; re-encountering a known `url_canonical` is a skip, so the stored title
  goes stale. Accepted for v1 (title drift is cosmetic); revisit if it bugs us.
- **Same story, republished at a new URL on the same site** (slug edit): URL
  dedup misses it, SimHash catches it.
- **Feed gives relative URLs**: resolve against the feed's channel link.
- **Item `<guid>` vs `<link>`**: always use `<link>` (guids are frequently
  non-URL strings or permalink-false).
- **One feed listing the same link twice** (happens): in-run seen-set.

### 5.3 Publish-date resolution

Priority: feed entry `publishedDate` → page `article:published_time` meta →
feed `updatedDate` → **fetch time as last resort**. Then:

- Timestamps normalized to UTC on write; templates render in ET like the rest
  of the site.
- **Future dates** (bad CMS clocks, timezone bugs): clamp to `now()` if more
  than 1 h ahead.
- **Ancient dates** (feeds that resurface evergreen content): items older than
  `news.max-item-age-days` (default 14) at discovery are discarded — this also
  naturally bounds the first poll of a newly added source.
- `updated` bumps in feeds do **not** re-ingest a known URL (see §5.2).

### 5.4 Sport filter (non-dedicated sources)

For sources where `dedicated_cbb=false` (general sports feeds, Google News
sweeps), an item is kept only if, over title + subtitle + body text:

- it matches at least one team/conference alias **and** at least one basketball
  context keyword (`basketball`, `NCAA tournament`, `March Madness`, `hoops`,
  `Final Four`, `bracket`, coachspeak terms — configurable list), **and**
- it does *not* match the exclusion list (`football`, `women's basketball`,
  `WBB`, `volleyball`, `baseball`, ... — also configurable), unless basketball
  keywords outnumber exclusion hits (mixed roundup articles).

Whether women's basketball is excluded, separately tagged, or included is an
**open question** (§9) — the filter is configuration, so the pipeline doesn't
care. The football case matters most: "Alabama lands five-star recruit" with
no sport context must not hit the site. Bias toward discarding: a missed CBB
story costs little; a football story on a team page looks broken.

Failed-fetch items from non-dedicated sources (metadata only, no body) get the
same filter over title+subtitle alone; that's a thin signal, so in practice
most survive only from dedicated sources. Fine.

### 5.5 Dedup (SimHash layer)

- 64-bit SimHash over shingled tokens (2-grams) of the extracted body,
  lowercased, punctuation-stripped.
- **Short-body guard:** if `body_token_count < 80` (paywall stub, video page,
  fetch failure), `simhash` is null and the article only participates in URL
  dedup. A 40-token stub would collide with every other 40-token stub
  otherwise.
- **Comparison window:** brute-force Hamming distance against articles from
  the **last 10 days** with non-null simhash (a few thousand rows at CBB news
  volume; in-memory scan per candidate is microseconds). No LSH, no index.
- Threshold: Hamming ≤ 3 ⇒ same story.
- **Representative selection:** the incoming article joins the cluster of the
  first match found. Representative = highest `authority_weight` in the
  cluster, tie-broken by earliest `published_at` (wire original usually beats
  the republisher on both). If the newcomer outranks the current
  representative, **re-point**: set the old representative's
  `duplicate_of_article_id` to the newcomer and repoint all cluster members
  (single UPDATE). Tags are recomputed on the new representative; listings
  always show representatives only (`duplicate_of_article_id IS NULL`).
- Hash collisions across genuinely different stories (two 400-word game recaps
  with heavy shared boilerplate) are possible. Mitigation: extraction strips
  nav/boilerplate before hashing, and the admin article view (§7.3) can break
  a cluster (`duplicate_of_article_id = null` + a `no_dedup` exemption is
  **not** stored in v1 — breaking a cluster is manual and could re-form on the
  next poll; noted in Open Questions).

### 5.6 Gazetteer construction (`TeamTagger`)

At startup and on demand (`/admin/news/aliases/reseed`), auto-generate `AUTO`
aliases per **active** team:

- `name` ("North Carolina Tar Heels" style full name, and the `name` alone)
- `nickname` (ESPN's short display name)
- `name + " " + mascot` and `nickname + " " + mascot`
- `mascot` alone — **seeded as `ambiguous=true` by default** (mascots are the
  main collision source: Wildcats×5, Tigers×6, Aggies×4, Bulldogs×lots)
- `abbreviation` — `case_sensitive=true`, and only if length ≥ 3 (drop 2-char)

Reseeding **upserts** `AUTO` rows and never touches `MANUAL`/`BLOCK` rows; an
`AUTO` row the admin has edited is flipped to `MANUAL` by the edit and thereby
pinned. Aliases whose text collides across teams (e.g. mascot "Wildcats" →
Kentucky, Arizona, Villanova, ...) are all seeded, all `ambiguous=true` —
resolution happens at match time (§5.7).

The Aho-Corasick automaton is rebuilt from the union of enabled aliases on
startup and after any alias mutation (it's a few thousand strings; rebuild is
milliseconds — no incremental maintenance).

**Matching rules:**

- Whole-word boundaries enforced (post-check on the automaton hit: preceding
  and following chars must be non-alphanumeric). "Cal" must not fire inside
  "Calipari"; "Duke" must not fire inside "Dukes".
- Case-insensitive except `case_sensitive` aliases (abbreviations: "UNC",
  "USC", "VCU" — lowercase "unc" in a URL slug is noise).
- Longest-match-wins on overlapping hits ("North Carolina State" must not also
  fire "North Carolina"). The Bor library supports this; add a post-filter
  regardless.
- Possessives count ("Gonzaga's win" matches Gonzaga).

### 5.7 Ambiguity resolution

Match-time confidence per (article, team):

```
score = 3.0 * unambiguous_title_hits
      + 1.0 * unambiguous_body_hits   (capped at 4)
      + 1.5 * ambiguous_hit           IF corroborated (see below)
confidence = min(1.0, score / 5.0);  tag iff score >= 2.0   (thresholds configurable)
```

- An **ambiguous** alias hit ("Wildcats", "Miami", "Washington") contributes
  **only if corroborated**: the same article also has an unambiguous hit for
  the same team, or (for the multi-team-mascot case) exactly one of the
  candidate teams has *any* other alias hit. "Miami" alone tags nothing;
  "Miami" + "Hurricanes" tags Miami FL; "Miami" + "RedHawks" tags Miami OH;
  "Miami" + "Heat" tags nothing (Heat isn't in the gazetteer — good).
- **Wire datelines** ("LAWRENCE, Kan. (AP) —") produce city-name hits we
  deliberately don't seed: cities are not in the auto gazetteer at all. Admins
  can add a city as a `MANUAL` ambiguous alias where it's genuinely useful.
- **State-name schools** ("Washington", "Kansas", "Houston", "Memphis"): the
  bare `name` is seeded but flagged `ambiguous=true` by a hardcoded seed-time
  list of collision-prone names (state names, big-city names). Corroboration
  ("Kansas Jayhawks", "Kansas" + "Bill Self" once a coach alias is added)
  promotes them.
- **Game recaps mention both teams** — correct behavior, both get tagged.
  Roundup articles ("Ten takeaways from Saturday") legitimately tag many
  teams; no cap, but confidence scales down naturally since each team gets few
  mentions.
- Coach names: not auto-seeded (not in DB). Supported as `MANUAL` aliases —
  admins add high-value ones ("Bill Self" → Kansas). Staleness when coaches
  move is the admin's burden; the alias page shows creation dates to help.

### 5.8 Conference tagging (`ConferenceResolver`)

- Direct hits: conference `name` + `abbreviation` seeded into the same
  automaton namespace (with the same ambiguity machinery — "Big 12" is safe,
  "American" and "Ivy" are `ambiguous`, "ACC"/"SEC" are `case_sensitive`).
- Derived: each tagged team contributes its conference **for the season the
  article falls in** — resolved via `ConferenceMembership` using the same
  season-attribution convention as games (a July article belongs to the
  *upcoming* season; reuse the season-date logic from `ScrapingProperties`
  rather than inventing a new rule). Derived conference confidence = 0.5 ×
  max team confidence, so direct conference stories outrank incidental
  mentions.
- Conference **renames** (WAC→UAC): current `Conference.name` seeds the
  gazetteer, and superseded names from `conference_name_history` are seeded
  too — an article saying "WAC tournament" should still tag the conference.
  Display on conference pages already goes through `ConferenceNamingService`.
- Realignment stories mentioning six conferences: all get tagged; fine.

### 5.9 Politeness & fetch behavior (`NewsHttpClient`)

- Config prefix `news.scraping.*` mirroring `espn.scraping.*`:
  `base-delay-ms` (default 500), `jitter-ms` (250), `schedule` (cron, default
  every 30 min), `max-item-age-days`, `poll-cap-per-source`,
  `default-authority-weight`, `timeout-ms` (10 000).
- Rate limiting is **per-host**, not global — 20 articles from 20 domains
  needn't serialize, but 20 ESPN articles must.
- Honest User-Agent: `YottoFijNewsBot/1.0 (+https://<site>/about)` — feeds are
  the sanctioned path; the article fetch is one page per new item, which is
  well within polite behavior, but we identify ourselves.
- **robots.txt honored for article fetches** on `HTML_INDEX`-type sources and
  for aggregator-discovered domains; direct-feed items are fetched without a
  robots check (publishing a feed is an invitation to fetch the linked pages)
  — flagged in Open Questions since reasonable people disagree.
- Response size cap (2 MB) and content-type check (`text/html` only) before
  parsing; non-HTML (PDF, video pages returning players) → metadata-only item.
- Character encoding: trust HTTP header, fall back to meta charset, fall back
  to UTF-8 (jsoup handles this chain natively).
- Conditional GET on feeds via stored ETag/Last-Modified. Feeds that 304
  cost one request per poll.
- **Source health:** `consecutive_failures` ≥ 10 → set `auto_disabled_at`,
  skip until an admin re-enables (or a weekly retry — Open Question). Feed
  parse errors count as failures; empty-but-valid feeds do not.

### 5.10 Scoring & ranking

Stored `static_score` = `authority_weight` (0–100, from source or default) —
tag confidence deliberately does **not** enter the global score (an article
isn't globally better because it's confidently about Kansas); confidence is
used per-tag for team/conference page filtering and ordering.

Read-time display score (in SQL, `NewsQueryService`):

```sql
static_score * exp(-EXTRACT(epoch FROM (now() - published_at)) / :halflife)
```

Half-life default 36 h (config `news.ranking.half-life-hours`). Effects: an
ESPN story beats a blog for a day and a half, then ages out; nothing pinned,
no recompute job, no stale scores.

Front page: top 6 representatives overall, with a **per-source cap of 2** so a
bursty source can't own the panel. `/news`: paginated, filter by team /
conference / source, default order display-score, secondary order
`published_at`. Team page: articles where `news_article_teams.team_id = ?
AND confidence >= 0.4`, ordered by display score, top 5 + "more" link to
filtered `/news`. Conference page: same shape.

Offseason (June–October): volume drops ~10×; the decay makes the front-page
panel go stale rather than empty. The panel simply shows older items —
acceptable, but the template should render "3 days ago" honestly rather than
hiding dates.

### 5.11 Images

- `og:image` only, absolutized against the page URL. No image download or
  caching in v1 (Open Question: proxy/cache).
- Templates must treat images as best-effort: `onerror` hides the `<img>` and
  the card layout must not depend on the image existing (hotlink-blocking and
  CDN expiry make some fraction of images break, guaranteed).
- Skip obviously-wrong images: og:image that is a site logo (heuristic:
  URL contains `logo`/`default`/`favicon`, or duplicate of the source's most
  common image URL — track nothing in v1, just the URL heuristic).

### 5.12 Retention

Articles are small rows; keep everything for v1 (a season is maybe 50–100 k
rows). `UserMaintenanceJob`-style pruning can come later. Tag rows cascade if
an article is ever deleted by an admin.

## 6. Public UI

- **Front page** (`HomeController`): "Latest News" card, top-6 query above.
  Title links out (target=_blank, `rel="noopener"`), source name + relative
  time shown. External-link affordance so users know they're leaving.
- **`/news`** (`NewsWebController`): card grid, HTMX-paginated, filter chips
  for conference → team drilldown reusing existing team/conference selector
  patterns. This page is public (no auth), like the rest of the site.
- **Team page** (`TeamWebController`): "News" section under the existing
  content, only rendered when ≥1 article matches.
- **Conference page**: same.
- All listings exclude `duplicate_of_article_id IS NOT NULL` and, optionally
  ("show all coverage" expander on a story card), reveal cluster members —
  nice v1.5, not v1.

## 7. Admin workflow

All under `/admin/news` (`AdminNewsController`, ADMIN role via existing
security config). Three surfaces, in workflow order:

### 7.1 Sources (`/admin/news/sources`)

- Table: name, domain, type, weight, dedicated flag, active/auto-disabled,
  last success, consecutive failures, articles-last-7-days.
- **Add source form** with a **"Test" button**: before saving, the server
  fetches and parses the feed and renders (HTMX fragment) the first 10 parsed
  items — title, resolved URL, date, and *what the sport filter and tagger
  would do to each* (kept/discarded + tags). This dry-run is the single most
  important admin feature: it catches wrong feed URLs, non-CBB feeds
  mislabeled as dedicated, and date-format weirdness before a source ever
  pollutes the DB.
- Edit weight/dedicated/active inline; disable ≠ delete (history keeps FK).
- Re-enable clears `auto_disabled_at` and `consecutive_failures`.
- Delete only for sources with zero articles; otherwise deactivate.

### 7.2 Tagging quality (`/admin/news/tagging`)

The improvement loop for team mapping. One page, three panels:

1. **Untagged recent articles** (tag_status=UNTAGGED, last 7 days): each row
   shows title + subtitle and a team/conference picker. Admin assigns tags →
   rows written with `manual=true`, `tag_status=MANUAL`. Crucially, the form
   offers a one-click **"...and create alias"**: after picking the team, the
   admin can select a phrase from the title and save it as a `MANUAL` alias
   (pre-filled, editable, with an `ambiguous` checkbox). This is how the
   gazetteer actually gets better — every manual fix can become a rule.
2. **Low-confidence tags** (confidence in [0.2, 0.4), i.e. near-misses that
   did *not* tag): shows what almost matched and via which alias. Admin
   confirms (→ manual tag, optionally promote/de-ambiguate the alias) or
   dismisses. This surfaces the ambiguous-alias corroboration failures.
3. **Alias table browser**: search/filter aliases, edit, disable, add `BLOCK`
   rows, see per-alias hit counts over the last 30 days (join against
   `matched_via`) — an alias with 500 hits across 40 different teams' articles
   is a de-facto stopword and the hit count makes that visible. "Reseed AUTO
   aliases" button (safe: never touches MANUAL/BLOCK).

**Retag operation** (`POST /admin/news/retag`, async, ScrapeBatch type NEWS):
re-runs the tagger over stored **title + subtitle only** for the last N days
(param, default 30). Because we don't keep bodies, retag is weaker than
initial tagging — it can add tags from title/subtitle matches and will
**never remove** existing tags, and never touches `manual` rows or `MANUAL`
articles. Use case: after adding "Bill Self → Kansas", pull in the last
month's headlines that mention him. The asymmetry (add-only) is deliberate:
removal on thinner evidence than the original decision would be wrong. A
full-strength retag would require re-fetching bodies; offered as a per-article
"refetch & retag" button on the article admin view, not in bulk.

### 7.3 Articles (`/admin/news/articles`)

- Search/browse all articles including suppressed duplicates; per-article
  view: tags with confidence + `matched_via`, cluster members, static score.
- Actions: edit tags (manual add/remove), **break cluster** (clear
  `duplicate_of_article_id` — may re-form on later polls, see Open
  Questions), **hide article** (admin kill switch: a `hidden` boolean, checked
  in all public queries — for the inevitable miscategorized or embarrassing
  item), refetch & retag.

  (`hidden boolean NOT NULL DEFAULT false` — add to §3.2.)

### 7.4 Dashboard integration

`/admin` gets a News card: last poll time/status (from ScrapeBatch NEWS rows,
visible in the existing scrape-history HTMX fragment automatically), counts
(articles today, untagged today, sources failing), links to the three pages
above. Manual "Poll now" button → `AsyncScrapeService`-style trigger, same
double-run guard pattern as the game scraper.

## 8. Testing

- `UrlCanonicalizer`, `SimHasher`, tag-confidence math: pure unit tests, table
  driven; this is where most edge cases above get pinned.
- `TeamTagger`: unit tests against a fixture gazetteer covering the ambiguity
  matrix (Miami×2, Wildcats×N, "Cal"/"Calipari" boundary, USC case
  sensitivity, longest-match NC State).
- `FeedPoller`/`ArticleFetcher`: `@MockBean NewsHttpClient` (the whole reason
  the client wrapper exists), fixture RSS XML + HTML files under
  `src/test/resources/news/` — include a Google-News-style redirect fixture,
  an AMP page with `rel=canonical`, a feed with garbage dates, a paywalled
  stub.
- End-to-end: `BaseIntegrationTest` + mocked client: poll twice, assert
  idempotency (second poll creates nothing); insert a republished wire pair,
  assert clustering and representative choice; respect existing FK-safe
  cleanup ordering in `@BeforeEach` (news tables delete before teams).

## 9. Open questions

1. **Women's basketball.** Exclude (v1 default via filter config), or ingest
   and tag with a `sport` column for a future WBB section? Deciding later is
   cheap only if we decide *before* accumulating data we'd have to backfill.
<br/>**Comment** Exclude women's basketball
2. **Google News as a source.** It fills the long tail but its ToS around
   automated consumption of the RSS output are murkier than direct feeds, and
   its redirect-resolution cost is real. Ship v1 with direct feeds only and
   add the sweep once the pipeline is proven? (My lean: yes — start with
   ~10–15 hand-picked feeds.)
<br/>** Comment**Skip for V1
3. **robots.txt for feed-linked article fetches** (§5.9): current position is
   "a feed link is an invitation"; the conservative position checks robots
   for every fetch. Cheap to flip either way — decide before launch.
<br/>** Comment**Skip (with a switch).  If is seems we are getting blocked hit robots every time
4. **`ScrapeBatch.seasonYear` is NOT NULL** but news is season-agnostic.
   Convention proposed: current season year. Alternative: relax the column.
   Cosmetic, but it shows up in the admin scrape-history table.
<br/>**Comment** Your proposal - current season year
5. **Cluster breaks re-forming.** Admin breaks a false-positive cluster; the
   next poll's dedup scan may re-link them. Needs either a `no_cluster`
   exemption pair table (clean fix, small table) or acceptance. Punt to v1.1
   unless false positives show up early.
<br/>**Comment** Punt to V1.1
6. **Image proxy/cache.** Hotlinking leaks user IPs to third parties, breaks
   randomly, and some sites block it. A tiny cached image proxy (resize +
   store on disk) fixes all three but adds storage and a moderation surface
   (we'd be re-serving others' images — copyright posture is weaker than for
   text). v1 hotlinks with graceful failure; revisit with data on breakage %.
<br/>**Comment** Cache tiny images.
7. **Auto-disabled source retry.** Permanent-until-admin vs. weekly automatic
   retry probe. Lean: weekly probe that flips it back on after one success,
   ntfy alert (existing Netdata/ntfy channel) when a source auto-disables.
<br>**Comment** Weekly retry
8. **Body-text storage for retag.** Storing extracted body text (even
   compressed, even for 30 days) would make retag full-strength and enable
   future topic rules — but it crosses the copyright line we drew. Current
   answer: no. Re-fetch on demand covers the per-article case. Revisit only
   with legal comfort.
<br/>**Comment** No storage
9. **Tag-time coach/player aliases at scale.** Manual curation works for ~50
   marquee names. If we ever want full coverage, rosters/coaches would come
   from an ESPN scrape (new entity) — that's a separate feature with its own
   design, not an extension of the alias table.
<br/>**Comment** Punt to V2
10. **Snippet length & fair use.** 300-char subtitle cap is a guess; some og:
    descriptions are the entire first paragraph. Trim at sentence boundary
    ≤300 chars. Anyone with stronger fair-use intuition should sanity-check.
<br/>**Comment** Fine
11. **Where does "Latest News" sit on the front page** relative to the
    existing scoreboard/predictions layout? Needs a quick mock before
    building `HomeController` changes.
<br/>**Comment** Sure show the mock, but generally toward the bottom.  Home page may need a redesign in the future

## 10. Rollout plan

1. **V29 migration + entities + repositories** (no behavior).
2. **Pipeline core**: client, canonicalizer, poller, fetcher, dedup, tagger —
   behind config flag `news.enabled=false` by default; unit + integration
   tests green.
3. **Admin surfaces** (sources with dry-run test button first — needed to
   onboard feeds safely), then tagging page, then articles page.
4. **Seed ~10 sources** in prod via admin UI (ESPN CBB, CBS Sports CBB, Field
   of 68, conference sites...), let it run headless for a few days, watch
   tagging quality via the admin pages.
5. **Public UI** last: `/news`, then team/conference sections, then front
   page. By then scoring/tagging has real data behind it.

## 11. Implementation notes (deviations from the draft)

- **Alias table is `news_aliases`, not `news_team_aliases`** — it holds both
  team and conference aliases (exactly one of team_id/conference_id set,
  DB CHECK enforced), so conference names/abbreviations and superseded
  brandings from `conference_name_history` share the same curation machinery.
- **SimHash Hamming threshold defaults to 10, not 3.** Measured on
  realistic article-length text: a verbatim republish with different site
  chrome lands at Hamming 4–8 (the classic ≤3 rule assumes full-page-length
  inputs), while genuinely different stories measure ~25–32. Configurable via
  `news.dedup.hamming-threshold`.
- **Ambiguity corroboration** (§5.7) uses the doc's "any other alias hit"
  rule: two distinct ambiguous aliases for the same team corroborate each
  other ("Kansas" + "Jayhawks" tags Kansas; a bare "Miami" tags nothing).
- **Near-misses are stored**, not discarded: tag rows below display
  confidence (0.4) but above the near-miss floor (score ≥ 1.0) are written and
  surfaced on the admin tagging page; public queries filter on confidence.
- **Thumbnails are cached locally** (answer to open question 6): og:image is
  downloaded at ingest, resized to 320 px JPEG under `news.images.thumbnail-dir`
  (compose volume `news_thumbnails`), and served from `/news/img/{articleId}`.
  No hotlinking; failures degrade to text-only cards.
- **Tag join tables** use a surrogate bigserial PK + UNIQUE(article, target)
  instead of composite PKs (JPA friendliness); semantics are identical.
- Women's basketball excluded via the sport filter; Google News deferred;
  robots.txt off by default behind `news.respect-robots`; auto-disabled
  sources get a weekly retry probe (`news.retry-probe-days`); `ScrapeBatch`
  NEWS rows use the current season year.
- Key config: `news.enabled` (master switch, default false; `NEWS_ENABLED`
  env), `news.schedule` (default every 30 min). Full knob list in
  `NewsProperties`.
- **URL-path sport verdict** (added after live-feed testing): the canonical URL
  is checked for sport markers before keyword filtering. A clear other-sport
  path (`/college-football/`, `womens-college-basketball`, ...) discards the
  item even from dedicated feeds (ESPN's "ncb" feed carries football items); a
  clear CBB path (`mens-college-basketball`, `/college-basketball/`, `/ncb/`)
  keeps it without needing keywords. Discard markers are checked first —
  "womens-college-basketball" contains "mens-college-basketball" as a substring.
- **Body extraction picks the richest `<article>`**, not the first — ESPN
  precedes the story with an empty ad `<article>`, which broke extraction
  (0 body tokens → no simhash, keyword filter starved) until this fix.
- **SSRF guard**: every fetch hop (feed URLs, article links, redirect targets,
  image downloads) resolves the host and rejects loopback, link-local (cloud
  metadata), private, any-local, multicast, and IPv6 unique-local addresses —
  feed content is third-party input and must never reach compose-internal
  services or the management port. Dev-only escape hatch:
  `news.allow-private-addresses`. Known residual: validation resolves DNS
  separately from the connection (a rebinding attacker could race the two);
  accepted for v1 given the curated-source model.
