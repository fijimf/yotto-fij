# Scatter Plot Matrix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Scatter Matrix" tab to `/rankings/comprehensive` that renders an 8x8 D3 scatter plot matrix of all power-ranking numeric variables for a given season and date.

**Architecture:** New HTMX fragment endpoint on `ComprehensiveRankingsController` serializes `ComprehensiveRankingRow` data to JSON via Jackson; fragment contains the data in a `<script type="application/json">` block; a self-contained D3 IIFE reads the data, computes Pearson r for all 28 variable pairs, and renders the matrix lazily via `IntersectionObserver`. Dots are colored by conference using a custom 33-color palette. Hovering shows a tooltip; clicking highlights the team across all cells. All user-supplied strings are written via `textContent` to avoid XSS.

**Tech Stack:** Java 17, Spring Boot 3.2, Thymeleaf, HTMX, D3 v7 (CDN), vanilla CSS.

---

## File Map

| Action | File |
|---|---|
| Modify | `src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java` |
| Modify | `src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java` |
| Modify | `src/main/resources/templates/pages/comprehensive-rankings.html` |
| Create | `src/main/resources/templates/fragments/scatter-matrix.html` |
| Modify | `src/main/resources/static/css/main.css` |

---

## Task 1: Controller — scatter matrix endpoint

**Files:**
- Modify: `src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java`
- Modify: `src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java`

- [ ] **Step 1.1: Write the failing tests**

Add these two test methods to `ComprehensiveRankingsControllerTest`, after the existing `tableFragment_withData_hasDataTrue` test:

```java
// -- GET /rankings/comprehensive/{year}/scatter-matrix ---------------------

@Test
void scatterMatrix_unknownYear_hasDataFalse() throws Exception {
    mockMvc.perform(get("/rankings/comprehensive/9999/scatter-matrix")
                    .param("date", SNAP_DATE.toString()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("hasData", false));
}

@Test
void scatterMatrix_withData_hasDataTrueAndJsonContainsTeam() throws Exception {
    addSeasonStats(teamA);
    addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
    addRating(teamA, MasseyRatingService.MODEL_TYPE, 12.5);

    var result = mockMvc.perform(get("/rankings/comprehensive/2025/scatter-matrix")
                    .param("date", SNAP_DATE.toString()))
            .andExpect(status().isOk())
            .andExpect(model().attribute("hasData", true))
            .andReturn();
    String json = (String) result.getModelAndView().getModel().get("scatterDataJson");
    org.assertj.core.api.Assertions.assertThat(json).contains("Alabama");
    org.assertj.core.api.Assertions.assertThat(json).contains("winPct");
    org.assertj.core.api.Assertions.assertThat(json).contains("massey");
}
```

- [ ] **Step 1.2: Run the tests to confirm they fail**

```bash
./mvnw test -pl . -Dtest="ComprehensiveRankingsControllerTest#scatterMatrix_unknownYear_hasDataFalse+scatterMatrix_withData_hasDataTrueAndJsonContainsTeam" -q
```

Expected: two failures — no handler mapped for `/rankings/comprehensive/9999/scatter-matrix`.

- [ ] **Step 1.3: Implement the endpoint**

Add imports at the top of `ComprehensiveRankingsController.java` (after the existing import block):

```java
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
```

Add `ObjectMapper` field and update the constructor. Replace the existing constructor:

```java
public ComprehensiveRankingsController(SeasonRepository seasonRepository,
                                       TeamPowerRatingSnapshotRepository ratingRepository,
                                       TeamSeasonStatSnapshotRepository statSnapshotRepository,
                                       SeasonStatisticsRepository seasonStatisticsRepository) {
    this.seasonRepository = seasonRepository;
    this.ratingRepository = ratingRepository;
    this.statSnapshotRepository = statSnapshotRepository;
    this.seasonStatisticsRepository = seasonStatisticsRepository;
}
```

With:

```java
private final ObjectMapper objectMapper;

public ComprehensiveRankingsController(SeasonRepository seasonRepository,
                                       TeamPowerRatingSnapshotRepository ratingRepository,
                                       TeamSeasonStatSnapshotRepository statSnapshotRepository,
                                       SeasonStatisticsRepository seasonStatisticsRepository,
                                       ObjectMapper objectMapper) {
    this.seasonRepository = seasonRepository;
    this.ratingRepository = ratingRepository;
    this.statSnapshotRepository = statSnapshotRepository;
    this.seasonStatisticsRepository = seasonStatisticsRepository;
    this.objectMapper = objectMapper;
}
```

Add the new handler method after the existing `rankingsTable` method (before `populateModel`):

```java
@GetMapping("/rankings/comprehensive/{year}/scatter-matrix")
public String scatterMatrix(@PathVariable Integer year,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                            Model model) {
    Season season = seasonRepository.findByYear(year).orElse(null);
    if (season == null) {
        model.addAttribute("hasData", false);
        return "fragments/scatter-matrix :: scatter-matrix";
    }
    LocalDate resolvedDate = resolveDate(season, date);
    List<ComprehensiveRankingRow> rows = resolvedDate != null ? buildRows(season, resolvedDate) : List.of();
    model.addAttribute("scatterDataJson", buildScatterJson(rows));
    model.addAttribute("hasData", !rows.isEmpty());
    return "fragments/scatter-matrix :: scatter-matrix";
}
```

Add the `buildScatterJson` private method before the existing `resolveSeason` method:

```java
private String buildScatterJson(List<ComprehensiveRankingRow> rows) {
    List<Map<String, Object>> data = rows.stream().map(r -> {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.team().getId());
        m.put("name", r.team().getName());
        m.put("conf", r.conferenceAbbr());
        m.put("winPct", r.winPct());
        m.put("ppg", r.meanPtsFor());
        m.put("opp", r.meanPtsAgainst());
        m.put("margin", r.meanMargin());
        m.put("rpi", r.rpi());
        m.put("massey", r.masseyRating());
        m.put("bt", r.bradleyTerryRating());
        m.put("btw", r.bradleyTerryWeightedRating());
        return m;
    }).toList();
    try {
        return objectMapper.writeValueAsString(data);
    } catch (JsonProcessingException e) {
        return "[]";
    }
}
```

- [ ] **Step 1.4: Run the tests to confirm they pass**

```bash
./mvnw test -pl . -Dtest=ComprehensiveRankingsControllerTest -q
```

Expected: all tests pass (the two new + all existing).

- [ ] **Step 1.5: Commit**

```bash
git add src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java \
        src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java
git commit -m "feat: add scatter matrix endpoint to ComprehensiveRankingsController"
```

---

## Task 2: CSS — tab strip and scatter matrix styles

**Files:**
- Modify: `src/main/resources/static/css/main.css`

- [ ] **Step 2.1: Append styles to the end of main.css**

```css

/* -- Comp. Rankings Tab Strip ---------------------------------------------- */
.comp-tab-strip {
    display: flex;
    gap: 0;
    border-bottom: 2px solid var(--color-border);
    margin-bottom: 1rem;
}
.comp-tab {
    padding: 8px 20px;
    background: none;
    border: none;
    border-bottom: 2px solid transparent;
    margin-bottom: -2px;
    color: var(--color-text-muted);
    font-size: 0.85rem;
    cursor: pointer;
    transition: color 0.15s;
}
.comp-tab:hover { color: var(--color-text); }
.comp-tab--active {
    color: var(--color-text);
    border-bottom-color: var(--color-primary-hover);
    font-weight: 600;
}

/* -- Scatter Matrix --------------------------------------------------------- */
.scatter-matrix-wrap {
    overflow-x: auto;
    overflow-y: auto;
    max-height: 760px;
    margin-bottom: 1rem;
}
.scatter-matrix-grid {
    display: grid;
    grid-template-columns: repeat(8, 90px);
    grid-template-rows: repeat(8, 90px);
    gap: 2px;
    background: var(--color-bg);
}
.scatter-cell {
    width: 90px;
    height: 90px;
    background: var(--color-bg-surface);
    position: relative;
    overflow: hidden;
}
.scatter-cell--diag {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    background: var(--color-bg);
    border: 1px solid var(--color-border);
}
.scatter-diag-label {
    font-size: 0.85rem;
    font-weight: 700;
    color: var(--color-text);
}
.scatter-diag-range {
    font-size: 0.62rem;
    color: var(--color-text-muted);
    margin-top: 3px;
    text-align: center;
    padding: 0 6px;
    line-height: 1.3;
}
.scatter-cell--corr {
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: default;
}
.scatter-corr-val {
    font-size: 0.82rem;
    font-weight: 700;
    color: var(--color-text);
    font-variant-numeric: tabular-nums;
}
.scatter-corr--pos-light  { background: rgba(46, 204, 113, 0.12); }
.scatter-corr--pos-medium { background: rgba(46, 204, 113, 0.28); }
.scatter-corr--pos-strong { background: rgba(46, 204, 113, 0.50); }
.scatter-corr--neg-light  { background: rgba(231,  76,  60, 0.12); }
.scatter-corr--neg-medium { background: rgba(231,  76,  60, 0.28); }
.scatter-corr--neg-strong { background: rgba(231,  76,  60, 0.50); }
.scatter-dot { cursor: pointer; }
.scatter-selected-label {
    font-size: 0.82rem;
    color: #f5c030;
    margin-bottom: 0.5rem;
    font-weight: 600;
}
.scatter-selected-label::before {
    content: 'Selected: ';
    color: var(--color-text-muted);
    font-weight: normal;
}
.scatter-tooltip {
    position: fixed;
    background: var(--color-bg-surface);
    border: 1px solid var(--color-border);
    border-radius: 4px;
    padding: 6px 10px;
    font-size: 0.78rem;
    line-height: 1.6;
    pointer-events: none;
    z-index: 200;
    max-width: 190px;
    box-shadow: 0 2px 8px rgba(0,0,0,0.4);
    color: var(--color-text);
}
.scatter-tooltip-conf { color: var(--color-text-muted); }
.scatter-legend {
    display: flex;
    flex-wrap: wrap;
    gap: 6px 14px;
    margin-top: 1rem;
    font-size: 0.75rem;
}
.scatter-legend-item {
    display: flex;
    align-items: center;
    gap: 5px;
    color: var(--color-text-muted);
}
.scatter-legend-swatch {
    width: 10px;
    height: 10px;
    border-radius: 50%;
    flex-shrink: 0;
}
```

- [ ] **Step 2.2: Commit**

```bash
git add src/main/resources/static/css/main.css
git commit -m "feat: add tab strip and scatter matrix CSS styles"
```

---

## Task 3: Tab strip on the comprehensive-rankings page

**Files:**
- Modify: `src/main/resources/templates/pages/comprehensive-rankings.html`

- [ ] **Step 3.1: Replace the season content block**

The current `<div th:if="${season != null}">` block in `comprehensive-rankings.html` contains the date picker, warning, and `comp-rankings-container`. Replace that entire block with the version below. The date picker markup is preserved verbatim; new additions are the tab strip, the scatter container, and the tab-switching script.

```html
    <div th:if="${season != null}">

        <div class="analytics-date-row" th:if="${latestDate != null}">
            <label class="form-label" for="comp-rankings-date">As of:</label>
            <select id="comp-rankings-date"
                    class="form-input form-input--sm"
                    style="width: auto;"
                    hx-get="@{'/rankings/comprehensive/' + ${season.year} + '/table'}"
                    hx-target="#comp-rankings-container"
                    hx-swap="innerHTML"
                    hx-include="this"
                    name="date">
                <option th:each="d : ${availableDates}"
                        th:value="${d}"
                        th:text="${d}"
                        th:selected="${selectedDate != null and d.equals(selectedDate)}">2026-03-01</option>
            </select>
            <span style="font-size: 0.85rem; color: var(--color-text-muted);"
                  th:text="'Latest: ' + ${latestDate}">Latest: 2026-03-14</span>
        </div>

        <div th:if="${latestDate == null}" class="alert alert--warning" style="margin-bottom: 1.5rem;">
            No ratings calculated yet for this season. Use the admin panel to run a power ratings calculation.
        </div>

        <div th:if="${latestDate != null}">

            <!-- Tab strip -->
            <div class="comp-tab-strip">
                <button class="comp-tab comp-tab--active"
                        id="comp-tab-table"
                        onclick="switchCompTab('table')">Table</button>
                <button class="comp-tab"
                        id="comp-tab-scatter"
                        onclick="switchCompTab('scatter')">Scatter Matrix</button>
            </div>

            <!-- Table tab (pre-loaded on page render) -->
            <div id="comp-rankings-container">
                <div th:replace="~{fragments/comprehensive-rankings-table :: comp-rankings-table}"></div>
            </div>

            <!-- Scatter matrix tab (lazy-loaded via HTMX on first click) -->
            <div id="scatter-matrix-container"
                 th:attr="data-year=${season.year}, data-date=${selectedDate}"
                 style="display:none">
            </div>

        </div>

    </div>

<script th:inline="none">
(function () {
    var scatterLoaded = false;

    window.switchCompTab = function (tabName) {
        document.querySelectorAll('.comp-tab').forEach(function (btn) {
            btn.classList.remove('comp-tab--active');
        });
        var activeBtn = document.getElementById('comp-tab-' + tabName);
        if (activeBtn) activeBtn.classList.add('comp-tab--active');

        var tableContainer   = document.getElementById('comp-rankings-container');
        var scatterContainer = document.getElementById('scatter-matrix-container');
        if (tableContainer)   tableContainer.style.display   = (tabName === 'table')   ? '' : 'none';
        if (scatterContainer) scatterContainer.style.display = (tabName === 'scatter') ? '' : 'none';

        if (tabName === 'scatter' && !scatterLoaded && scatterContainer) {
            var year = scatterContainer.dataset.year;
            var date = scatterContainer.dataset.date;
            htmx.ajax('GET',
                '/rankings/comprehensive/' + year + '/scatter-matrix?date=' + date,
                { target: '#scatter-matrix-container', swap: 'innerHTML' });
            scatterLoaded = true;
        }
    };

    // When date picker swaps the table, clear scatter so it reloads fresh
    document.body.addEventListener('htmx:afterSwap', function (evt) {
        if (evt.detail.target && evt.detail.target.id === 'comp-rankings-container') {
            var scatterContainer = document.getElementById('scatter-matrix-container');
            if (scatterContainer) {
                scatterContainer.textContent = '';
                var dateSelect = document.getElementById('comp-rankings-date');
                if (dateSelect) scatterContainer.dataset.date = dateSelect.value;
            }
            scatterLoaded = false;
            switchCompTab('table');
        }
    });
})();
</script>
```

- [ ] **Step 3.2: Verify the application compiles**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3.3: Commit**

```bash
git add src/main/resources/templates/pages/comprehensive-rankings.html
git commit -m "feat: add Table/Scatter Matrix tab strip to comprehensive-rankings page"
```

---

## Task 4: Scatter matrix fragment with D3

**Files:**
- Create: `src/main/resources/templates/fragments/scatter-matrix.html`

- [ ] **Step 4.1: Create the fragment**

Create `src/main/resources/templates/fragments/scatter-matrix.html` with the content below. The render script uses only `textContent` and `setAttribute` for user-supplied data (team names, conference names) to avoid XSS. The only `innerHTML` usage is for the `<script type="application/json">` data block rendered by Thymeleaf `th:text`, which HTML-encodes the JSON safely.

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>

<div th:fragment="scatter-matrix">

    <div th:if="${!hasData}" class="teams-empty">
        No data available for this selection.
    </div>

    <div th:if="${hasData}">

        <!-- JSON data — Thymeleaf th:text HTML-encodes; JS reads via textContent which decodes -->
        <script id="scatter-data" type="application/json" th:text="${scatterDataJson}">[]</script>

        <!-- Selected-team banner -->
        <div class="scatter-selected-label" id="scatter-selected-label" style="display:none"></div>

        <!-- Matrix -->
        <div class="scatter-matrix-wrap">
            <div id="scatter-matrix-root" class="scatter-matrix-grid"></div>
        </div>

        <!-- Legend (populated by JS) -->
        <div id="scatter-legend" class="scatter-legend"></div>

        <!-- Tooltip -->
        <div class="scatter-tooltip" id="scatter-tooltip" style="display:none"></div>

        <script th:inline="none">
        (function () {
            var CELL = 90;
            var VARS = [
                { key: 'winPct',  label: 'W%',     fmt: function(v) { return v.toFixed(3); } },
                { key: 'ppg',     label: 'PPG',    fmt: function(v) { return v.toFixed(1); } },
                { key: 'opp',     label: 'OPP',    fmt: function(v) { return v.toFixed(1); } },
                { key: 'margin',  label: '+/-',    fmt: function(v) { return (v >= 0 ? '+' : '') + v.toFixed(1); } },
                { key: 'rpi',     label: 'RPI',    fmt: function(v) { return v.toFixed(4); } },
                { key: 'massey',  label: 'Massey', fmt: function(v) { return (v >= 0 ? '+' : '') + v.toFixed(2); } },
                { key: 'bt',      label: 'B-T',    fmt: function(v) { return v.toFixed(3); } },
                { key: 'btw',     label: 'BTW',    fmt: function(v) { return v.toFixed(3); } }
            ];
            var N = VARS.length;

            var COLORS = [
                '#1A6FD4','#4BAED4','#A0D4F0','#0C3060','#1D9E75','#5DCAA5','#0F6E56','#7DC232',
                '#3B6D11','#97C459','#6B8E23','#C0DD97','#EFB527','#BA7517','#F5C030','#F07A2A',
                '#C0471A','#E07060','#C0272D','#8B1A1A','#7F77DD','#534AB7','#AFA9EC','#6A2BA0',
                '#D4537E','#B0306A','#ED93B1','#444441','#888780','#B4B2A9','#A0522D','#8B7355','#C8B89A'
            ];

            function loadD3AndRender() {
                if (window.d3) {
                    render();
                } else {
                    var s = document.createElement('script');
                    s.src = 'https://cdn.jsdelivr.net/npm/d3@7/dist/d3.min.js';
                    s.onload = render;
                    document.head.appendChild(s);
                }
            }

            function render() {
                var raw = JSON.parse(document.getElementById('scatter-data').textContent || '[]');
                if (!raw.length) return;

                /* conference color map */
                var confs = Array.from(new Set(raw.map(function(d) { return d.conf; }))).sort();
                var confColorMap = {};
                confs.forEach(function(c, i) { confColorMap[c] = COLORS[i % COLORS.length]; });
                function dotColor(d) { return confColorMap[d.conf] || '#888'; }

                /* Pearson r */
                function pearsonR(data, xKey, yKey) {
                    var valid = data.filter(function(d) { return d[xKey] != null && d[yKey] != null; });
                    if (valid.length < 2) return null;
                    var xs = valid.map(function(d) { return d[xKey]; });
                    var ys = valid.map(function(d) { return d[yKey]; });
                    var xm = xs.reduce(function(a,b){return a+b;},0) / xs.length;
                    var ym = ys.reduce(function(a,b){return a+b;},0) / ys.length;
                    var num = 0, dxSq = 0, dySq = 0;
                    for (var i = 0; i < xs.length; i++) {
                        var dx = xs[i] - xm, dy = ys[i] - ym;
                        num += dx * dy; dxSq += dx * dx; dySq += dy * dy;
                    }
                    var den = Math.sqrt(dxSq * dySq);
                    return den === 0 ? 0 : num / den;
                }

                var rMatrix = {};
                for (var ri = 0; ri < N; ri++) {
                    for (var ci = 0; ci < N; ci++) {
                        if (ri !== ci) rMatrix[ri + ',' + ci] = pearsonR(raw, VARS[ci].key, VARS[ri].key);
                    }
                }

                function corrClass(r) {
                    if (r === null) return '';
                    var abs = Math.abs(r), dir = r > 0 ? 'pos' : 'neg';
                    if (abs < 0.3) return '';
                    var str = abs < 0.6 ? 'light' : abs < 0.8 ? 'medium' : 'strong';
                    return 'scatter-corr--' + dir + '-' + str;
                }

                /* tooltip — all user text via textContent */
                var tooltip = document.getElementById('scatter-tooltip');

                function showDotTooltip(event, d, xVar, yVar) {
                    var nameEl = document.createElement('strong');
                    nameEl.textContent = d.name;
                    var confEl = document.createElement('div');
                    confEl.className = 'scatter-tooltip-conf';
                    confEl.textContent = d.conf;
                    var xEl = document.createElement('div');
                    xEl.textContent = xVar.label + ': ' + (d[xVar.key] != null ? xVar.fmt(d[xVar.key]) : '\u2014');
                    var yEl = document.createElement('div');
                    yEl.textContent = yVar.label + ': ' + (d[yVar.key] != null ? yVar.fmt(d[yVar.key]) : '\u2014');
                    tooltip.textContent = '';
                    tooltip.append(nameEl, confEl, xEl, yEl);
                    tooltip.style.display = 'block';
                    moveTooltip(event);
                }

                function showCorrTooltip(event, xVar, yVar, r) {
                    var titleEl = document.createElement('strong');
                    titleEl.textContent = xVar.label + ' vs ' + yVar.label;
                    var rEl = document.createElement('div');
                    rEl.textContent = 'Pearson r: ' + (r >= 0 ? '+' : '') + r.toFixed(4);
                    tooltip.textContent = '';
                    tooltip.append(titleEl, rEl);
                    tooltip.style.display = 'block';
                    moveTooltip(event);
                }

                function hideTooltip() { tooltip.style.display = 'none'; }
                function moveTooltip(event) {
                    tooltip.style.left = (event.clientX + 14) + 'px';
                    tooltip.style.top  = (event.clientY + 14) + 'px';
                }

                /* highlight state */
                var selectedTeamId = null;
                var allDots = [];

                function applyHighlight(teamId) {
                    allDots.forEach(function(dot) {
                        var id = +dot.getAttribute('data-team-id');
                        var sel = id === teamId;
                        dot.classList.toggle('scatter-dot--selected', sel);
                        dot.classList.toggle('scatter-dot--dimmed', !sel);
                        dot.setAttribute('r', sel ? 5 : 2.5);
                        dot.setAttribute('opacity', sel ? 1 : 0.12);
                    });
                }

                function clearHighlight() {
                    selectedTeamId = null;
                    var lbl = document.getElementById('scatter-selected-label');
                    if (lbl) { lbl.style.display = 'none'; lbl.textContent = ''; }
                    allDots.forEach(function(dot) {
                        dot.classList.remove('scatter-dot--selected', 'scatter-dot--dimmed');
                        dot.setAttribute('r', 2.5);
                        dot.setAttribute('opacity', 0.7);
                    });
                }

                function toggleHighlight(teamId, teamName) {
                    if (selectedTeamId === teamId) {
                        clearHighlight();
                    } else {
                        selectedTeamId = teamId;
                        var lbl = document.getElementById('scatter-selected-label');
                        if (lbl) { lbl.textContent = teamName; lbl.style.display = 'block'; }
                        applyHighlight(teamId);
                    }
                }

                document.addEventListener('keydown', function(e) {
                    if (e.key === 'Escape') clearHighlight();
                });

                /* cell renderers */
                function renderDiagonal(cell, v) {
                    cell.classList.add('scatter-cell--diag');
                    var valid = raw.filter(function(d) { return d[v.key] != null; })
                                   .map(function(d) { return d[v.key]; });
                    var minV = d3.min(valid), maxV = d3.max(valid);
                    var labelDiv = document.createElement('div');
                    labelDiv.className = 'scatter-diag-label';
                    labelDiv.textContent = v.label;
                    var rangeDiv = document.createElement('div');
                    rangeDiv.className = 'scatter-diag-range';
                    rangeDiv.textContent =
                        (minV != null ? v.fmt(minV) : '\u2014') + ' \u2013 ' +
                        (maxV != null ? v.fmt(maxV) : '\u2014');
                    cell.append(labelDiv, rangeDiv);
                }

                function renderScatter(cell, xVar, yVar) {
                    cell.classList.add('scatter-cell--scatter');
                    var valid = raw.filter(function(d) {
                        return d[xVar.key] != null && d[yVar.key] != null;
                    });
                    if (!valid.length) return;
                    var pad = 8;
                    var xScale = d3.scaleLinear()
                        .domain(d3.extent(valid, function(d) { return d[xVar.key]; }))
                        .range([pad, CELL - pad]).nice();
                    var yScale = d3.scaleLinear()
                        .domain(d3.extent(valid, function(d) { return d[yVar.key]; }))
                        .range([CELL - pad, pad]).nice();

                    var svg = d3.select(cell).append('svg')
                        .attr('width', CELL).attr('height', CELL);

                    xScale.ticks(3).forEach(function(t) {
                        svg.append('line')
                            .attr('x1', xScale(t)).attr('x2', xScale(t))
                            .attr('y1', CELL - pad - 2).attr('y2', CELL - pad + 2)
                            .attr('stroke', '#444').attr('stroke-width', 0.5);
                    });
                    yScale.ticks(3).forEach(function(t) {
                        svg.append('line')
                            .attr('x1', pad - 2).attr('x2', pad + 2)
                            .attr('y1', yScale(t)).attr('y2', yScale(t))
                            .attr('stroke', '#444').attr('stroke-width', 0.5);
                    });

                    var circles = svg.selectAll('circle').data(valid).enter().append('circle')
                        .attr('cx', function(d) { return xScale(d[xVar.key]); })
                        .attr('cy', function(d) { return yScale(d[yVar.key]); })
                        .attr('r', 2.5)
                        .attr('fill', dotColor)
                        .attr('opacity', 0.7)
                        .attr('class', 'scatter-dot')
                        .attr('data-team-id', function(d) { return d.id; })
                        .on('mouseover', function(event, d) { showDotTooltip(event, d, xVar, yVar); })
                        .on('mousemove', moveTooltip)
                        .on('mouseout', hideTooltip)
                        .on('click', function(event, d) {
                            event.stopPropagation();
                            toggleHighlight(d.id, d.name);
                        });

                    allDots.push.apply(allDots, circles.nodes());
                    svg.on('click', clearHighlight);
                }

                function renderCorr(cell, row, col, xVar, yVar) {
                    cell.classList.add('scatter-cell--corr');
                    var r = rMatrix[row + ',' + col];
                    var cls = corrClass(r);
                    if (cls) cell.classList.add(cls);
                    var valDiv = document.createElement('div');
                    valDiv.className = 'scatter-corr-val';
                    valDiv.textContent = r !== null ? (r >= 0 ? '+' : '') + r.toFixed(2) : '\u2014';
                    cell.appendChild(valDiv);
                    cell.addEventListener('mouseover', function(event) {
                        if (r != null) showCorrTooltip(event, xVar, yVar, r);
                    });
                    cell.addEventListener('mousemove', moveTooltip);
                    cell.addEventListener('mouseout', hideTooltip);
                }

                function renderCell(cell) {
                    var row = +cell.dataset.row;
                    var col = +cell.dataset.col;
                    if (row === col)    renderDiagonal(cell, VARS[col]);
                    else if (row > col) renderScatter(cell, VARS[col], VARS[row]);
                    else                renderCorr(cell, row, col, VARS[col], VARS[row]);
                }

                /* build grid with lazy IntersectionObserver */
                var grid = document.getElementById('scatter-matrix-root');
                if (!grid) return;

                var observer = new IntersectionObserver(function(entries) {
                    entries.forEach(function(entry) {
                        if (entry.isIntersecting) {
                            renderCell(entry.target);
                            observer.unobserve(entry.target);
                        }
                    });
                }, { rootMargin: '120px' });

                for (var row = 0; row < N; row++) {
                    for (var col = 0; col < N; col++) {
                        var cellEl = document.createElement('div');
                        cellEl.className = 'scatter-cell';
                        cellEl.dataset.row = row;
                        cellEl.dataset.col = col;
                        grid.appendChild(cellEl);
                        observer.observe(cellEl);
                    }
                }

                /* conference legend — all user strings via textContent */
                var legend = document.getElementById('scatter-legend');
                if (legend) {
                    confs.forEach(function(conf) {
                        var item = document.createElement('div');
                        item.className = 'scatter-legend-item';
                        var swatch = document.createElement('div');
                        swatch.className = 'scatter-legend-swatch';
                        swatch.style.background = confColorMap[conf];
                        var label = document.createElement('span');
                        label.textContent = conf;
                        item.append(swatch, label);
                        legend.appendChild(item);
                    });
                }
            } // end render()

            loadD3AndRender();
        })();
        </script>

    </div>

</div>

</body>
</html>
```

- [ ] **Step 4.2: Verify the application compiles**

```bash
./mvnw compile -q
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 4.3: Run the full test suite**

```bash
./mvnw test -q
```

Expected: `BUILD SUCCESS`, all tests pass.

- [ ] **Step 4.4: Commit**

```bash
git add src/main/resources/templates/fragments/scatter-matrix.html
git commit -m "feat: add scatter-matrix Thymeleaf fragment with D3 SPLOM rendering"
```

---

## Task 5: Manual verification checklist

Start the app: `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
Navigate to: `http://localhost:8080/rankings/comprehensive`

- [ ] Table tab is active by default; existing table renders correctly.
- [ ] Click "Scatter Matrix" tab — grid of cells appears (some may render as they scroll into view).
- [ ] **Diagonal cells** show variable label (W%, PPG, OPP, +/-, RPI, Massey, B-T, BTW) and a min-max range.
- [ ] **Lower triangle** — scatter plots with colored dots; 3 tick marks visible per axis.
- [ ] **Upper triangle** — shows +.xx or -.xx values; cells with |r| > 0.3 have a green or red tint; |r| > 0.8 cells are noticeably saturated.
- [ ] Hover a scatter dot — tooltip shows team name, conference, x-value, y-value.
- [ ] Hover a correlation cell — tooltip shows variable pair and full r to 4 decimal places.
- [ ] Click a scatter dot — that dot enlarges with a gold ring across every cell; all others dim; selected-team label appears above matrix.
- [ ] Click the same dot again — highlight clears.
- [ ] Press Escape — highlight clears.
- [ ] Change the date picker — table refreshes; switching to Scatter Matrix tab fetches fresh data.
- [ ] Switch back to Table tab — table still present (no re-fetch).
- [ ] Conference legend appears below the matrix with correct colors matching the dots.
- [ ] Narrow viewport — matrix scrolls horizontally without breaking page layout.
