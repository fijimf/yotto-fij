package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.StatCatalog;
import com.yotto.basketball.service.StatCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * Statistics-section pages from the menu reorganization (spec §5): category
 * landing pages, the predictor index/detail split, and the correlation
 * explorer. Also pins the /stats/{x} namespace rules.
 */
@AutoConfigureMockMvc
class StatisticsSectionPagesTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamStatSnapshotRepository statSnapshotRepo;
    @Autowired UserRepository userRepo;

    static final LocalDate SNAP = LocalDate.of(2025, 1, 24);

    Season season;
    Team teamA;
    Team teamB;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Baylor", "TB");
        mkStat(teamA, "wp", 0.9, 1);
        mkStat(teamB, "wp", 0.4, 2);
        mkStat(teamA, "ppg", 85.2, 1);
        mkStat(teamB, "ppg", 70.1, 2);
        mkStat(teamA, "efg_pct", 0.55, 1);

        // Earlier snapshots + later FINAL games so predictiveness (AUC) actually
        // computes — exercises the AUC-bar rendering path on the index page
        mkStatOn(teamA, "wp", 0.8, LocalDate.of(2025, 1, 5));
        mkStatOn(teamB, "wp", 0.3, LocalDate.of(2025, 1, 5));
        mkGame(teamA, teamB, 80, 60, LocalDate.of(2025, 1, 10), "G1");
        mkGame(teamB, teamA, 55, 75, LocalDate.of(2025, 1, 12), "G2");
    }

    @Autowired com.yotto.basketball.repository.GameRepository gameRepo;

    private void mkGame(Team home, Team away, int hs, int as, LocalDate date, String espnId) {
        com.yotto.basketball.entity.Game g = new com.yotto.basketball.entity.Game();
        g.setSeason(season);
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setGameDate(date.atTime(19, 0));
        g.setStatus(com.yotto.basketball.entity.Game.GameStatus.FINAL);
        g.setEspnId(espnId);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        gameRepo.save(g);
    }

    private void mkStatOn(Team team, String statName, double value, LocalDate date) {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(date);
        s.setStatName(statName);
        s.setValue(value);
        s.setGamesPlayed(5);
        s.setRank(1);
        statSnapshotRepo.save(s);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkStat(Team team, String statName, double value, int rank) {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(SNAP);
        s.setStatName(statName);
        s.setValue(value);
        s.setGamesPlayed(10);
        s.setRank(rank);
        statSnapshotRepo.save(s);
    }

    // ── Namespace rules ───────────────────────────────────────────────────────

    @Test
    void statNamespace_categorySlugsReservedWordsAndStatNamesAreDisjoint() {
        for (StatCategory c : StatCategory.values()) {
            assertNull(statNameOrNull(c.getSlug()),
                    "category slug collides with a stat name: " + c.getSlug());
            assertTrue(!StatCategory.RESERVED_SLUGS.contains(c.getSlug()),
                    "category slug collides with a reserved word: " + c.getSlug());
        }
        for (String reserved : StatCategory.RESERVED_SLUGS) {
            assertNull(statNameOrNull(reserved),
                    "reserved word collides with a stat name: " + reserved);
        }
    }

    private String statNameOrNull(String name) {
        return StatCatalog.contains(name) ? name : null;
    }

    // ── Category pages ────────────────────────────────────────────────────────

    @Test
    void categoryPage_rendersLeaderCards() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/results"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/stat-category"))
                .andExpect(content().string(containsString("Win Percentage")))
                .andExpect(content().string(containsString("Alabama")))
                .andExpect(content().string(containsString("Full rankings")))
                // th:replace precedence regression: empty state must not render alongside data
                .andExpect(content().string(not(containsString("No snapshot data"))));
    }

    @Test
    void categoryPage_unscopedRedirectsToLatestSeason() throws Exception {
        mockMvc.perform(get("/stats/scoring"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seasons/2025/stats/scoring"));
    }

    @Test
    void categoryPage_unknownSlug_404s() throws Exception {
        mockMvc.perform(get("/stats/nonsense-category"))
                .andExpect(status().isNotFound());
    }

    @Test
    void categoryCardsFragment_swapsByDate() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/results/cards").param("date", SNAP.toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alabama")));
    }

    @Test
    void statDetail_breadcrumbLinksToCategory() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/efg_pct"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/seasons/2025/stats/four-factors")))
                // the scatter moved to the predictor page
                .andExpect(content().string(not(containsString("id=\"stat-scatter\""))))
                .andExpect(content().string(containsString("/stats/predictor/efg_pct")));
    }

    // ── Predictor ─────────────────────────────────────────────────────────────

    @Test
    void predictorIndex_rendersRankedTable() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/predictor"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/predictor-index"))
                .andExpect(content().string(containsString("The Predictor")))
                .andExpect(content().string(containsString("AUC")));
    }

    @Test
    void predictorDetail_rendersScatterShell() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/predictor/efg_pct"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/predictor-detail"))
                .andExpect(content().string(containsString("id=\"stat-scatter\"")))
                .andExpect(content().string(containsString("How useful is it?")));
    }

    @Test
    void predictorDetail_unknownStat_404s() throws Exception {
        mockMvc.perform(get("/stats/predictor/not_a_stat"))
                .andExpect(status().isNotFound());
    }

    // ── Correlation ───────────────────────────────────────────────────────────

    @Test
    void correlation_rendersWithDefaultSelection() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/correlation"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/correlation"))
                .andExpect(content().string(containsString("Correlation Explorer")))
                .andExpect(content().string(containsString("scatter-matrix-root")))
                .andExpect(content().string(containsString("MATRIX_DATA")));
    }

    @Test
    void correlation_urlVarsOverCapAreClamped() throws Exception {
        String tenVars = "wp,ppg,opp_ppg,scoring_margin,margin_volatility,owp,oowp,efg_pct,ts_pct,pace";
        mockMvc.perform(get("/seasons/2025/stats/correlation").param("vars", tenVars))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("8 of 8 selected")));
    }

    @Test
    void correlation_invalidVarsFallBackToDefault() throws Exception {
        mockMvc.perform(get("/seasons/2025/stats/correlation").param("vars", "bogus,junk"))
                .andExpect(status().isOk())
                // default selection includes massey
                .andExpect(content().string(containsString("value=\"massey\" checked")));
    }
}
