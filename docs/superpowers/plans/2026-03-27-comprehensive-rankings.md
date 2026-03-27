# Comprehensive Rankings Page Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `/rankings/comprehensive` page that shows all teams in one wide table with record, scoring, and all four model ratings — sortable and filterable client-side.

**Architecture:** A new `ComprehensiveRankingsController` fetches four data sources for a given season+date (`TeamSeasonStatSnapshot` for stats/RPI, `SeasonStatistics` for conference, and three `TeamPowerRatingSnapshot` model types), merges them by team ID into `ComprehensiveRankingRow` DTOs, and passes the list to a Thymeleaf template. The table renders with grouped column headers (Record / Scoring / Model Ratings) and vanilla-JS client-side sort + filter.

**Tech Stack:** Java 17 records (DTO), Spring MVC (controller), Thymeleaf + HTMX (templates), vanilla JS (sort/filter), CSS custom properties (theming).

---

## File Map

| Action | File | Responsibility |
|---|---|---|
| Create | `src/main/java/com/yotto/basketball/dto/ComprehensiveRankingRow.java` | DTO record — one row of the table |
| Create | `src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java` | Routes + data assembly |
| Create | `src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java` | Integration tests |
| Create | `src/main/resources/templates/pages/comprehensive-rankings.html` | Page shell (season picker, date picker, HTMX container) |
| Create | `src/main/resources/templates/fragments/comprehensive-rankings-table.html` | Table fragment with JS sort/filter |
| Modify | `src/main/resources/static/css/main.css` | `.comp-rankings-*` styles |
| Modify | `src/main/resources/templates/fragments/nav.html` | Add "Comp. Rankings" nav link |
| Modify | `.gitignore` | Add `.superpowers/` |

---

## Task 1: Add `.superpowers/` to `.gitignore`

**Files:**
- Modify: `.gitignore`

- [ ] **Step 1: Add the entry**

Open `.gitignore` and append:

```
# Visual brainstorming session artifacts
.superpowers/
```

- [ ] **Step 2: Commit**

```bash
git add .gitignore
git commit -m "chore: ignore .superpowers/ brainstorm artifacts"
```

---

## Task 2: `ComprehensiveRankingRow` DTO

**Files:**
- Create: `src/main/java/com/yotto/basketball/dto/ComprehensiveRankingRow.java`

- [ ] **Step 1: Create the record**

```java
package com.yotto.basketball.dto;

import com.yotto.basketball.entity.Team;

public record ComprehensiveRankingRow(
        Team team,
        String conferenceName,
        String conferenceAbbr,
        Integer wins,
        Integer losses,
        Double winPct,
        Double meanPtsFor,
        Double meanPtsAgainst,
        Double meanMargin,
        Double rpi,
        Double masseyRating,
        Double bradleyTerryRating,
        Double bradleyTerryWeightedRating
) {}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/yotto/basketball/dto/ComprehensiveRankingRow.java
git commit -m "feat: add ComprehensiveRankingRow DTO"
```

---

## Task 3: `ComprehensiveRankingsController` (TDD)

**Files:**
- Create: `src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java`
- Create: `src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java`

The controller needs `SeasonRepository`, `TeamPowerRatingSnapshotRepository`, `TeamSeasonStatSnapshotRepository`, and `SeasonStatisticsRepository`. It merges four data sources by team ID.

- [ ] **Step 1: Write the failing test class**

Create `src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java`:

```java
package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.dto.ComprehensiveRankingRow;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.MasseyRatingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ComprehensiveRankingsControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Season season;
    Conference sec;
    Team teamA, teamB;
    static final LocalDate SNAP_DATE = LocalDate.of(2025, 1, 14);

    @BeforeEach
    void setUp() {
        popStatRepo.deleteAll();
        snapshotRepo.deleteAll();
        oddsRepo.deleteAll();
        paramRepo.deleteAll();
        ratingRepo.deleteAll();
        statsRepo.deleteAll();
        gameRepo.deleteAll();
        membershipRepo.deleteAll();
        teamRepo.deleteAll();
        conferenceRepo.deleteAll();
        seasonRepo.deleteAll();

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = new Conference();
        sec.setName("Southeastern Conference");
        sec.setAbbreviation("SEC");
        sec.setEspnId("sec1");
        conferenceRepo.save(sec);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void addSeasonStats(Team team) {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(sec);
        statsRepo.save(ss);
    }

    private void addStatSnapshot(Team team, int wins, int losses, double winPct,
                                  double ptsFor, double ptsAgainst, double margin, double rpi) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(SNAP_DATE);
        s.setGamesPlayed(wins + losses);
        s.setWins(wins);
        s.setLosses(losses);
        s.setWinPct(winPct);
        s.setMeanPtsFor(ptsFor);
        s.setMeanPtsAgainst(ptsAgainst);
        s.setMeanMargin(margin);
        s.setRpi(rpi);
        snapshotRepo.save(s);
    }

    private void addRating(Team team, String modelType, double rating) {
        TeamPowerRatingSnapshot r = new TeamPowerRatingSnapshot();
        r.setTeam(team);
        r.setSeason(season);
        r.setModelType(modelType);
        r.setSnapshotDate(SNAP_DATE);
        r.setRating(rating);
        r.setGamesPlayed(20);
        r.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(r);
    }

    // ── GET /rankings/comprehensive ───────────────────────────────────────────

    @Test
    void noSeasons_returns200WithEmptyState() throws Exception {
        seasonRepo.deleteAll();
        mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/comprehensive-rankings"));
    }

    @Test
    void withSeason_noData_hasDataFalse() throws Exception {
        mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", false));
    }

    @Test
    void withData_rowsContainBothTeams() throws Exception {
        addSeasonStats(teamA);
        addSeasonStats(teamB);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        addStatSnapshot(teamB, 15, 10, 0.600, 72.0, 68.0,  4.0, 0.540);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 12.5);
        addRating(teamB, MasseyRatingService.MODEL_TYPE,  4.0);

        mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", true))
                .andExpect(model().attributeExists("rows"));

        // Verify both teams appear and Alabama (higher Massey) is first
        var result = mockMvc.perform(get("/rankings/comprehensive"))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ComprehensiveRankingRow> rows = (List<ComprehensiveRankingRow>)
                result.getModelAndView().getModel().get("rows");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).team().getName()).isEqualTo("Alabama");
    }

    @Test
    void teamWithNoRating_masseyRatingIsNull() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        // no addRating call — teamA has no Massey snapshot

        var result = mockMvc.perform(get("/rankings/comprehensive"))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ComprehensiveRankingRow> rows = (List<ComprehensiveRankingRow>)
                result.getModelAndView().getModel().get("rows");
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(rows.get(0).masseyRating()).isNull();
    }

    @Test
    void conferenceAbbrPopulatedFromSeasonStatistics() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);

        var result = mockMvc.perform(get("/rankings/comprehensive"))
                .andReturn();
        @SuppressWarnings("unchecked")
        List<ComprehensiveRankingRow> rows = (List<ComprehensiveRankingRow>)
                result.getModelAndView().getModel().get("rows");
        org.assertj.core.api.Assertions.assertThat(rows.get(0).conferenceAbbr()).isEqualTo("SEC");
    }

    // ── GET /rankings/comprehensive/{year}/table ──────────────────────────────

    @Test
    void tableFragment_unknownYear_returns200() throws Exception {
        mockMvc.perform(get("/rankings/comprehensive/9999/table")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void tableFragment_withData_hasDataTrue() throws Exception {
        addSeasonStats(teamA);
        addStatSnapshot(teamA, 20, 5, 0.800, 80.0, 65.0, 15.0, 0.620);
        addRating(teamA, MasseyRatingService.MODEL_TYPE, 12.5);

        mockMvc.perform(get("/rankings/comprehensive/2025/table")
                        .param("date", SNAP_DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("hasData", true));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./mvnw test -pl . -Dtest=ComprehensiveRankingsControllerTest -q 2>&1 | tail -20
```

Expected: compilation error — `ComprehensiveRankingsController` does not exist yet.

- [ ] **Step 3: Create the controller**

Create `src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java`:

```java
package com.yotto.basketball.controller;

import com.yotto.basketball.dto.ComprehensiveRankingRow;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.SeasonStatistics;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.service.BradleyTerryRatingService;
import com.yotto.basketball.service.MasseyRatingService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Controller
public class ComprehensiveRankingsController {

    private final SeasonRepository seasonRepository;
    private final TeamPowerRatingSnapshotRepository ratingRepository;
    private final TeamSeasonStatSnapshotRepository statSnapshotRepository;
    private final SeasonStatisticsRepository seasonStatisticsRepository;

    public ComprehensiveRankingsController(SeasonRepository seasonRepository,
                                           TeamPowerRatingSnapshotRepository ratingRepository,
                                           TeamSeasonStatSnapshotRepository statSnapshotRepository,
                                           SeasonStatisticsRepository seasonStatisticsRepository) {
        this.seasonRepository = seasonRepository;
        this.ratingRepository = ratingRepository;
        this.statSnapshotRepository = statSnapshotRepository;
        this.seasonStatisticsRepository = seasonStatisticsRepository;
    }

    @GetMapping("/rankings/comprehensive")
    public String rankings(@RequestParam(required = false) Integer year,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                           Model model) {
        List<Season> allSeasons = seasonRepository.findAll()
                .stream().sorted((a, b) -> b.getYear().compareTo(a.getYear())).toList();
        Season season = resolveSeason(year, allSeasons);
        model.addAttribute("currentPage", "comprehensive-rankings");
        if (season == null) {
            model.addAttribute("allSeasons", allSeasons);
            return "pages/comprehensive-rankings";
        }
        LocalDate resolvedDate = resolveDate(season, date);
        populateModel(season, resolvedDate, allSeasons, model);
        return "pages/comprehensive-rankings";
    }

    @GetMapping("/rankings/comprehensive/{year}/table")
    public String rankingsTable(@PathVariable Integer year,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                Model model) {
        Season season = seasonRepository.findByYear(year).orElse(null);
        if (season == null) {
            model.addAttribute("rows", List.of());
            model.addAttribute("hasData", false);
            return "fragments/comprehensive-rankings-table :: comp-rankings-table";
        }
        LocalDate resolvedDate = resolveDate(season, date);
        populateModel(season, resolvedDate, List.of(), model);
        return "fragments/comprehensive-rankings-table :: comp-rankings-table";
    }

    private void populateModel(Season season, LocalDate resolvedDate,
                               List<Season> allSeasons, Model model) {
        List<ComprehensiveRankingRow> rows = resolvedDate != null
                ? buildRows(season, resolvedDate) : List.of();

        List<LocalDate> availableDates = ratingRepository
                .findSnapshotDates(season.getId(), MasseyRatingService.MODEL_TYPE);
        List<LocalDate> availableDatesDesc = new ArrayList<>(availableDates);
        Collections.reverse(availableDatesDesc);

        model.addAttribute("season", season);
        model.addAttribute("allSeasons", allSeasons);
        model.addAttribute("selectedDate", resolvedDate);
        model.addAttribute("availableDates", availableDatesDesc);
        model.addAttribute("latestDate", availableDatesDesc.isEmpty() ? null : availableDatesDesc.get(0));
        model.addAttribute("rows", rows);
        model.addAttribute("hasData", !rows.isEmpty());
    }

    private List<ComprehensiveRankingRow> buildRows(Season season, LocalDate date) {
        List<TeamSeasonStatSnapshot> stats =
                statSnapshotRepository.findBySeasonAndDate(season.getId(), date);
        List<SeasonStatistics> seasonStats =
                seasonStatisticsRepository.findBySeasonIdWithTeamAndConference(season.getId());
        List<TeamPowerRatingSnapshot> massey =
                ratingRepository.findBySeasonModelAndDate(season.getId(), MasseyRatingService.MODEL_TYPE, date);
        List<TeamPowerRatingSnapshot> bt =
                ratingRepository.findBySeasonModelAndDate(season.getId(), BradleyTerryRatingService.MODEL_TYPE, date);
        List<TeamPowerRatingSnapshot> btw =
                ratingRepository.findBySeasonModelAndDate(season.getId(), BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, date);

        Map<Long, SeasonStatistics> confByTeam = seasonStats.stream()
                .collect(Collectors.toMap(ss -> ss.getTeam().getId(), ss -> ss, (a, b) -> a));
        Map<Long, Double> masseyByTeam = massey.stream()
                .collect(Collectors.toMap(r -> r.getTeam().getId(), TeamPowerRatingSnapshot::getRating, (a, b) -> a));
        Map<Long, Double> btByTeam = bt.stream()
                .collect(Collectors.toMap(r -> r.getTeam().getId(), TeamPowerRatingSnapshot::getRating, (a, b) -> a));
        Map<Long, Double> btwByTeam = btw.stream()
                .collect(Collectors.toMap(r -> r.getTeam().getId(), TeamPowerRatingSnapshot::getRating, (a, b) -> a));

        return stats.stream().map(s -> {
            long teamId = s.getTeam().getId();
            SeasonStatistics ss = confByTeam.get(teamId);
            String confName = ss != null ? ss.getConference().getName() : "—";
            String confAbbr = (ss != null && ss.getConference().getAbbreviation() != null)
                    ? ss.getConference().getAbbreviation() : confName;
            return new ComprehensiveRankingRow(
                    s.getTeam(), confName, confAbbr,
                    s.getWins(), s.getLosses(), s.getWinPct(),
                    s.getMeanPtsFor(), s.getMeanPtsAgainst(), s.getMeanMargin(), s.getRpi(),
                    masseyByTeam.get(teamId),
                    btByTeam.get(teamId),
                    btwByTeam.get(teamId)
            );
        })
        .sorted(Comparator.comparingDouble((ComprehensiveRankingRow r) ->
                r.masseyRating() != null ? r.masseyRating() : Double.NEGATIVE_INFINITY).reversed())
        .toList();
    }

    private Season resolveSeason(Integer year, List<Season> allSeasons) {
        if (year != null) return seasonRepository.findByYear(year).orElse(null);
        return allSeasons.isEmpty() ? null : allSeasons.get(0);
    }

    private LocalDate resolveDate(Season season, LocalDate requested) {
        if (requested != null) return requested;
        return ratingRepository.findLatestSnapshotDate(season.getId(), MasseyRatingService.MODEL_TYPE).orElse(null);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./mvnw test -pl . -Dtest=ComprehensiveRankingsControllerTest -q 2>&1 | tail -20
```

Expected: `BUILD SUCCESS`, all 8 tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/yotto/basketball/controller/ComprehensiveRankingsController.java \
        src/test/java/com/yotto/basketball/controller/ComprehensiveRankingsControllerTest.java
git commit -m "feat: add ComprehensiveRankingsController with tests"
```

---

## Task 4: Page template `comprehensive-rankings.html`

**Files:**
- Create: `src/main/resources/templates/pages/comprehensive-rankings.html`

This is the page shell — mirrors the structure of `pages/rankings.html` but with a different title, different fragment reference, and the `currentPage` value `"comprehensive-rankings"`.

- [ ] **Step 1: Create the template**

```html
<!DOCTYPE html>
<html lang="en"
      xmlns:th="http://www.thymeleaf.org"
      xmlns:layout="http://www.ultraq.net.nz/thymeleaf/layout"
      layout:decorate="~{layout/default}">
<head>
    <title th:text="${season != null} ? ${season.year} + ' Comprehensive Rankings' : 'Comprehensive Rankings'">
        Comprehensive Rankings
    </title>
</head>
<body>
<main layout:fragment="content">

    <div class="page-header">
        <h1 class="page-title"
            th:text="${season != null} ? ${season.year} + ' Comprehensive Rankings' : 'Comprehensive Rankings'">
            2026 Comprehensive Rankings
        </h1>
        <div class="page-header__actions">
            <select class="form-input form-input--sm"
                    onchange="window.location='/rankings/comprehensive?year='+this.value">
                <option value="" th:if="${season == null}">Select season</option>
                <option th:each="s : ${allSeasons}"
                        th:value="${s.year}"
                        th:text="${s.year}"
                        th:selected="${season != null and s.year == season.year}">2026</option>
            </select>
        </div>
    </div>

    <div th:if="${season == null}" class="teams-empty">
        No seasons found. Add a season in the admin panel to get started.
    </div>

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

        <div id="comp-rankings-container">
            <div th:replace="~{fragments/comprehensive-rankings-table :: comp-rankings-table}"></div>
        </div>

    </div>

</main>
</body>
</html>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/pages/comprehensive-rankings.html
git commit -m "feat: add comprehensive-rankings page template"
```

---

## Task 5: Table fragment with grouped headers, sort, and filter JS

**Files:**
- Create: `src/main/resources/templates/fragments/comprehensive-rankings-table.html`

This is the bulk of the UI work. It renders the filter bar, the grouped-header table, and contains all client-side sort/filter logic.

- [ ] **Step 1: Create the fragment**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<body>

<div th:fragment="comp-rankings-table">

    <div th:if="${!hasData}" class="teams-empty">
        No rankings data found for this selection.
    </div>

    <div th:if="${hasData}">

        <!-- Filter bar -->
        <div class="comp-rankings-filters">
            <input id="comp-filter-name"
                   class="form-input form-input--sm"
                   type="text"
                   placeholder="Filter team…"
                   oninput="compRankingsFilter()" />
            <select id="comp-filter-conf"
                    class="form-input form-input--sm"
                    onchange="compRankingsFilter()">
                <option value="">All conferences</option>
                <option th:each="conf : ${#lists.sort(rows.![conferenceAbbr].stream().distinct().sorted().toList())}"
                        th:value="${conf}"
                        th:text="${conf}">SEC</option>
            </select>
            <span class="comp-rankings-count" id="comp-rankings-count"></span>
        </div>

        <!-- Table -->
        <div class="comp-rankings-wrap">
            <table class="comp-rankings-table" id="comp-rankings-table">
                <thead>
                    <!-- Group label row -->
                    <tr class="comp-rankings-group-row">
                        <th class="comp-rankings__g-identity" colspan="3">Team</th>
                        <th class="comp-rankings__g-record" colspan="3">Record</th>
                        <th class="comp-rankings__g-scoring" colspan="3">Scoring</th>
                        <th class="comp-rankings__g-ratings" colspan="4">Model Ratings</th>
                    </tr>
                    <!-- Sortable column row -->
                    <tr class="comp-rankings-col-row">
                        <th class="comp-rankings__g-identity-col comp-rankings__col--rank"
                            onclick="compRankingsSort(0, 'num')">#</th>
                        <th class="comp-rankings__g-identity-col comp-rankings__col--team"
                            onclick="compRankingsSort(1, 'str')">Team</th>
                        <th class="comp-rankings__g-identity-col comp-rankings__col--conf"
                            onclick="compRankingsSort(2, 'str')">Conf</th>
                        <th class="comp-rankings__g-record-col" onclick="compRankingsSort(3, 'num')">W</th>
                        <th class="comp-rankings__g-record-col" onclick="compRankingsSort(4, 'num')">L</th>
                        <th class="comp-rankings__g-record-col" onclick="compRankingsSort(5, 'num')">W%</th>
                        <th class="comp-rankings__g-scoring-col" onclick="compRankingsSort(6, 'num')">PPG</th>
                        <th class="comp-rankings__g-scoring-col" onclick="compRankingsSort(7, 'num')">OPP</th>
                        <th class="comp-rankings__g-scoring-col" onclick="compRankingsSort(8, 'num')">±</th>
                        <th class="comp-rankings__g-ratings-col" onclick="compRankingsSort(9, 'num')">RPI</th>
                        <th class="comp-rankings__g-ratings-col comp-rankings__col--sort-active"
                            onclick="compRankingsSort(10, 'num')" id="comp-sort-massey">Massey ▼</th>
                        <th class="comp-rankings__g-ratings-col" onclick="compRankingsSort(11, 'num')">B-T</th>
                        <th class="comp-rankings__g-ratings-col" onclick="compRankingsSort(12, 'num')">BTW</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="r : ${rows}"
                        th:attr="data-conf=${r.conferenceAbbr}">
                        <!-- rank (row number, updated by JS after sort) -->
                        <td class="comp-rankings__cell--rank comp-rankings__g-identity-td" th:text="${rStat.count}">1</td>
                        <!-- team -->
                        <td class="comp-rankings__cell--team comp-rankings__g-identity-td">
                            <a th:href="@{'/teams/' + ${r.team.id}}" class="rankings-team-link">
                                <img th:if="${r.team.logoUrl != null}"
                                     th:src="${r.team.logoUrl}"
                                     th:alt="${r.team.name}"
                                     class="rankings-team-logo"
                                     loading="lazy" />
                                <span th:if="${r.team.logoUrl == null}"
                                      class="rankings-team-logo-placeholder"
                                      th:style="'background: #' + (${r.team.color} != null ? ${r.team.color} : '555')"
                                      th:text="${#strings.substring(r.team.name, 0, 1)}">A</span>
                                <span class="rankings-team-name" th:text="${r.team.name}">Team</span>
                            </a>
                        </td>
                        <!-- conf -->
                        <td class="comp-rankings__cell--conf comp-rankings__g-identity-td"
                            th:text="${r.conferenceAbbr}">SEC</td>
                        <!-- record -->
                        <td class="comp-rankings__g-record-td" th:text="${r.wins != null} ? ${r.wins} : '—'">20</td>
                        <td class="comp-rankings__g-record-td" th:text="${r.losses != null} ? ${r.losses} : '—'">5</td>
                        <td class="comp-rankings__g-record-td"
                            th:text="${r.winPct != null} ? ${#numbers.formatDecimal(r.winPct, 1, 3)} : '—'">.800</td>
                        <!-- scoring -->
                        <td class="comp-rankings__g-scoring-td"
                            th:text="${r.meanPtsFor != null} ? ${#numbers.formatDecimal(r.meanPtsFor, 1, 1)} : '—'">80.0</td>
                        <td class="comp-rankings__g-scoring-td"
                            th:text="${r.meanPtsAgainst != null} ? ${#numbers.formatDecimal(r.meanPtsAgainst, 1, 1)} : '—'">65.0</td>
                        <td class="comp-rankings__g-scoring-td"
                            th:classappend="${r.meanMargin != null and r.meanMargin > 0} ? 'rankings-rating--pos' : (${r.meanMargin != null and r.meanMargin < 0} ? 'rankings-rating--neg' : '')"
                            th:text="${r.meanMargin != null} ? (${r.meanMargin >= 0} ? '+' : '') + ${#numbers.formatDecimal(r.meanMargin, 1, 1)} : '—'">+15.0</td>
                        <!-- ratings -->
                        <td class="comp-rankings__g-ratings-td"
                            th:text="${r.rpi != null} ? ${#numbers.formatDecimal(r.rpi, 1, 4)} : '—'">.6200</td>
                        <td class="comp-rankings__g-ratings-td"
                            th:classappend="${r.masseyRating != null and r.masseyRating > 0} ? 'rankings-rating--pos' : (${r.masseyRating != null and r.masseyRating < 0} ? 'rankings-rating--neg' : '')"
                            th:text="${r.masseyRating != null} ? (${r.masseyRating >= 0} ? '+' : '') + ${#numbers.formatDecimal(r.masseyRating, 1, 2)} : '—'">+12.50</td>
                        <td class="comp-rankings__g-ratings-td"
                            th:text="${r.bradleyTerryRating != null} ? ${#numbers.formatDecimal(r.bradleyTerryRating, 1, 3)} : '—'">1.842</td>
                        <td class="comp-rankings__g-ratings-td"
                            th:text="${r.bradleyTerryWeightedRating != null} ? ${#numbers.formatDecimal(r.bradleyTerryWeightedRating, 1, 3)} : '—'">2.104</td>
                    </tr>
                </tbody>
            </table>
        </div>
        <!-- mobile note -->
        <p class="comp-rankings-scroll-hint">← scroll for more columns →</p>

    </div>

</div>

<script th:inline="none">
(function () {
    var sortCol = 10;   // default: Massey
    var sortDir = -1;   // -1 = desc, 1 = asc

    // Update visible row count display
    function updateCount() {
        var tbody = document.querySelector('#comp-rankings-table tbody');
        if (!tbody) return;
        var visible = Array.from(tbody.rows).filter(r => r.style.display !== 'none').length;
        var total   = tbody.rows.length;
        var el = document.getElementById('comp-rankings-count');
        if (el) el.textContent = visible === total ? total + ' teams' : visible + ' of ' + total + ' teams';
    }

    // Re-number rank column after sort/filter
    function renumberRanks() {
        var tbody = document.querySelector('#comp-rankings-table tbody');
        if (!tbody) return;
        var rank = 1;
        Array.from(tbody.rows).forEach(function (row) {
            if (row.style.display !== 'none') {
                row.cells[0].textContent = rank++;
            }
        });
    }

    window.compRankingsSort = function (colIdx, type) {
        var table = document.getElementById('comp-rankings-table');
        if (!table) return;

        // Toggle direction if same column, else default desc for numerics, asc for strings
        if (colIdx === sortCol) {
            sortDir = -sortDir;
        } else {
            sortDir = type === 'str' ? 1 : -1;
            sortCol = colIdx;
        }

        // Update header arrow indicators
        table.querySelectorAll('.comp-rankings-col-row th').forEach(function (th, i) {
            th.classList.toggle('comp-rankings__col--sort-active', i === colIdx);
            // Strip old arrows
            th.textContent = th.textContent.replace(/ [▲▼]$/, '');
            if (i === colIdx) th.textContent += (sortDir === -1 ? ' ▼' : ' ▲');
        });

        // Sort rows
        var tbody = table.querySelector('tbody');
        var rows = Array.from(tbody.rows);
        rows.sort(function (a, b) {
            var va = a.cells[colIdx] ? a.cells[colIdx].textContent.trim() : '';
            var vb = b.cells[colIdx] ? b.cells[colIdx].textContent.trim() : '';
            if (type === 'num') {
                var na = parseFloat(va.replace(/[+,]/g, ''));
                var nb = parseFloat(vb.replace(/[+,]/g, ''));
                var aNull = isNaN(na) || va === '—';
                var bNull = isNaN(nb) || vb === '—';
                if (aNull && bNull) return 0;
                if (aNull) return 1;   // nulls always last
                if (bNull) return -1;
                return sortDir * (na - nb);
            } else {
                return sortDir * va.localeCompare(vb);
            }
        });
        rows.forEach(function (r) { tbody.appendChild(r); });
        renumberRanks();
        updateCount();
    };

    window.compRankingsFilter = function () {
        var nameVal = (document.getElementById('comp-filter-name') || {}).value || '';
        var confVal = (document.getElementById('comp-filter-conf') || {}).value || '';
        nameVal = nameVal.toLowerCase();

        var tbody = document.querySelector('#comp-rankings-table tbody');
        if (!tbody) return;
        Array.from(tbody.rows).forEach(function (row) {
            var name = row.cells[1] ? row.cells[1].textContent.toLowerCase() : '';
            var conf = row.getAttribute('data-conf') || '';
            var matchName = !nameVal || name.includes(nameVal);
            var matchConf = !confVal || conf === confVal;
            row.style.display = (matchName && matchConf) ? '' : 'none';
        });
        renumberRanks();
        updateCount();
    };

    // Initialize count on load
    document.addEventListener('DOMContentLoaded', updateCount);
    // Also run immediately (HTMX swap may have already fired)
    updateCount();
})();
</script>

</body>
</html>
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/templates/fragments/comprehensive-rankings-table.html
git commit -m "feat: add comprehensive-rankings-table fragment with sort/filter JS"
```

---

## Task 6: CSS styles

**Files:**
- Modify: `src/main/resources/static/css/main.css`

Append the following block to the end of `main.css`. Do not edit existing rules.

- [ ] **Step 1: Append CSS to `main.css`**

```css
/* ═══════════════════════════════════════════════════════════════
   Comprehensive Rankings page
   ═══════════════════════════════════════════════════════════════ */

.comp-rankings-filters {
    display: flex;
    align-items: center;
    gap: 0.5rem;
    margin-bottom: 0.75rem;
    flex-wrap: wrap;
}

.comp-rankings-count {
    font-size: 0.7rem;
    color: var(--color-text-muted);
    margin-left: auto;
}

.comp-rankings-wrap {
    overflow-x: auto;
    -webkit-overflow-scrolling: touch;
}

.comp-rankings-table {
    width: 100%;
    border-collapse: collapse;
    font-size: 0.71rem;
    line-height: 1.2;
    white-space: nowrap;
}

/* ── Sticky identity columns (rank + team) ── */
.comp-rankings-table th:nth-child(1),
.comp-rankings-table td:nth-child(1) {
    position: sticky;
    left: 0;
    z-index: 2;
    background: var(--color-bg, #0f0f1a);
}
.comp-rankings-table th:nth-child(2),
.comp-rankings-table td:nth-child(2) {
    position: sticky;
    left: 32px;
    z-index: 2;
    background: var(--color-bg, #0f0f1a);
}
.comp-rankings-table tbody tr:hover td:nth-child(1),
.comp-rankings-table tbody tr:hover td:nth-child(2) {
    background: var(--color-surface-hover, #16162a);
}

/* ── Group label row ── */
.comp-rankings-group-row th {
    padding: 4px 7px;
    font-size: 0.6rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.07em;
    text-align: center;
    border-bottom: 2px solid;
}
.comp-rankings__g-identity {
    background: #131324;
    border-color: #1a1a2e;
    color: #5050a0;
    text-align: left;
}
.comp-rankings__g-record {
    background: #161630;
    border-color: #2a2a6a;
    color: #7070b8;
}
.comp-rankings__g-scoring {
    background: #152a1a;
    border-color: #1a5a2a;
    color: #507050;
}
.comp-rankings__g-ratings {
    background: #2a1a30;
    border-color: #5a2a6a;
    color: #8050a0;
}

/* ── Sortable column row ── */
.comp-rankings-col-row th {
    padding: 4px 7px;
    font-size: 0.64rem;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.03em;
    text-align: right;
    border-bottom: 1px solid #2a2a44;
    cursor: pointer;
    user-select: none;
    color: #8080b0;
}
.comp-rankings-col-row th:hover { color: #c0c0f0; }
.comp-rankings__col--sort-active { color: #a0a0f0 !important; }
.comp-rankings__g-identity-col { text-align: left; }
.comp-rankings__g-record-col   { background: rgba(22,22,48,0.5); }
.comp-rankings__g-scoring-col  { background: rgba(21,42,26,0.5); }
.comp-rankings__g-ratings-col  { background: rgba(42,26,48,0.5); }

/* ── Rank column ── */
.comp-rankings__col--rank { width: 32px; color: #5060a0; }
.comp-rankings__cell--rank { color: #5060a0; text-align: right; }

/* ── Team column ── */
.comp-rankings__col--team { min-width: 140px; }
.comp-rankings__cell--team { text-align: left; }

/* ── Conf column ── */
.comp-rankings__col--conf { width: 60px; }
.comp-rankings__cell--conf { text-align: left; color: #8080b0; }

/* ── Body rows ── */
.comp-rankings-table tbody tr {
    border-bottom: 1px solid #18182e;
}
.comp-rankings-table tbody tr:hover { background: var(--color-surface-hover, #16162a); }
.comp-rankings-table tbody td {
    padding: 5px 7px;
    text-align: right;
    color: #c8c8dc;
    font-variant-numeric: tabular-nums;
}

/* ── Group cell tints ── */
.comp-rankings__g-record-td   { background: rgba(22,22,48,0.25); }
.comp-rankings__g-scoring-td  { background: rgba(21,42,26,0.25); }
.comp-rankings__g-ratings-td  { background: rgba(42,26,48,0.25); }

/* ── Mobile scroll hint ── */
.comp-rankings-scroll-hint {
    font-size: 0.65rem;
    color: var(--color-text-muted);
    text-align: center;
    margin-top: 0.4rem;
    display: none;
}
@media (max-width: 900px) {
    .comp-rankings-scroll-hint { display: block; }
}
```

- [ ] **Step 2: Verify the app still starts**

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev -q &
sleep 8 && curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/rankings/comprehensive
```

Expected: `200`

Kill the test server: `kill %1`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/css/main.css
git commit -m "feat: add comp-rankings CSS styles"
```

---

## Task 7: Nav link

**Files:**
- Modify: `src/main/resources/templates/fragments/nav.html`

Add a `"Comp. Rankings"` link immediately after the existing `"Rankings"` link.

- [ ] **Step 1: Edit `nav.html`**

Find this block in `nav.html`:

```html
<li><a class="nav__link" th:href="@{/rankings}" th:classappend="${#strings.equals(currentPage, 'rankings')} ? 'nav__link--active'">Rankings</a></li>
```

Add immediately after it:

```html
<li><a class="nav__link" th:href="@{/rankings/comprehensive}" th:classappend="${#strings.equals(currentPage, 'comprehensive-rankings')} ? 'nav__link--active'">Comp. Rankings</a></li>
```

- [ ] **Step 2: Run the full test suite**

```bash
./mvnw test -q 2>&1 | tail -10
```

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/templates/fragments/nav.html
git commit -m "feat: add Comp. Rankings nav link"
```

---

## Self-Review

**Spec coverage check:**

| Spec requirement | Task |
|---|---|
| Route `/rankings/comprehensive` | Task 3 |
| HTMX fragment route `/rankings/comprehensive/{year}/table` | Task 3 |
| `ComprehensiveRankingRow` DTO merging four sources | Tasks 2 + 3 |
| Missing ratings render as `—` | Task 5 (Thymeleaf ternary) |
| Grouped headers (Record / Scoring / Model Ratings) | Tasks 5 + 6 |
| Color tints per group | Task 6 |
| Sticky rank + team columns | Task 6 |
| Sort by any column, default Massey desc | Task 5 (JS) |
| Filter by team name + conference | Task 5 (JS) |
| "Comp. Rankings" nav link | Task 7 |
| Season selector + date picker | Task 4 |
| `.superpowers/` in `.gitignore` | Task 1 |

All requirements covered. No TBDs or placeholders.
