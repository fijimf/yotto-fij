package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/** Per-model power ranking pages + the simplified /rankings overview (spec §6). */
@AutoConfigureMockMvc
class PowerRankingPageControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired TeamSeasonStatSnapshotRepository wideRepo;
    @Autowired com.yotto.basketball.repository.PowerModelParamSnapshotRepository paramRepo;

    static final LocalDate SNAP = LocalDate.of(2025, 2, 1);

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

        mkRating(teamA, "MASSEY", 12.4, 1, 20);
        mkRating(teamB, "MASSEY", -3.1, 2, 3); // low GP row

        // ADJ ratings are centered team params (higher def = stronger defense);
        // the page displays absolutes via the intercept params: AdjO = μ + off,
        // AdjD = μ − def, Net = off + def, Tempo = baseline + τ.
        mkRating(teamA, "ADJ_OFF", 8.2, 1, 20);
        mkRating(teamA, "ADJ_DEF", 5.0, 1, 20);
        mkRating(teamA, "ADJ_TEMPO", 0.5, 1, 20);
        mkParam("ADJ_OFF", "eff_intercept", 110.0);
        mkParam("ADJ_TEMPO", "tempo_intercept", 68.0);

        mkWide(teamA, 0.62, 18, 2);
        mkWide(teamB, 0.41, 9, 11);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkRating(Team team, String modelType, double rating, int rank, int gp) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(SNAP);
        s.setRating(rating);
        s.setRank(rank);
        s.setGamesPlayed(gp);
        s.setCalculatedAt(java.time.LocalDateTime.of(2025, 2, 1, 6, 0));
        ratingRepo.save(s);
    }

    private void mkParam(String modelType, String name, double value) {
        com.yotto.basketball.entity.PowerModelParamSnapshot p =
                new com.yotto.basketball.entity.PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setSnapshotDate(SNAP);
        p.setParamName(name);
        p.setParamValue(value);
        p.setCalculatedAt(java.time.LocalDateTime.of(2025, 2, 1, 6, 0));
        paramRepo.save(p);
    }

    private void mkWide(Team team, double rpi, int wins, int losses) {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(SNAP);
        s.setGamesPlayed(wins + losses);
        s.setWins(wins);
        s.setLosses(losses);
        s.setWinPct((double) wins / (wins + losses));
        s.setRpi(rpi);
        s.setRpiWp(rpi + 0.01);
        s.setRpiOwp(rpi - 0.01);
        s.setRpiOowp(rpi);
        wideRepo.save(s);
    }

    @Test
    void masseyPage_rendersRatedTableWithSignedRatings() throws Exception {
        mockMvc.perform(get("/seasons/2025/rankings/massey"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/power-ranking"))
                .andExpect(content().string(containsString("Massey Rankings")))
                .andExpect(content().string(containsString("+12.40")))
                .andExpect(content().string(containsString("18–2")))
                .andExpect(content().string(containsString("power-ranking-table__row--low-gp")));
    }

    @Test
    void rpiPage_rendersComponentColumns() throws Exception {
        mockMvc.perform(get("/seasons/2025/rankings/rpi"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("RPI Rankings")))
                .andExpect(content().string(containsString("OWP")))
                .andExpect(content().string(containsString("0.6200")))
                .andExpect(content().string(containsString("Alabama")));
    }

    @Test
    void adjustedEfficiencyPage_joinsOffDefTempo() throws Exception {
        mockMvc.perform(get("/seasons/2025/rankings/adjusted-efficiency"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AdjO")))
                .andExpect(content().string(containsString("118.2"))) // AdjO = 110 + 8.2
                .andExpect(content().string(containsString("105.0"))) // AdjD = 110 − 5.0
                .andExpect(content().string(containsString("+13.2"))) // Net = 8.2 + 5.0
                .andExpect(content().string(containsString("68.5")))  // Tempo = 68 + 0.5
                // Baylor has no ADJ rows — must be absent, not broken
                .andExpect(content().string(not(containsString("Baylor"))));
    }

    @Test
    void modelSlug_unscoped_redirectsToLatestSeason() throws Exception {
        mockMvc.perform(get("/rankings/massey"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seasons/2025/rankings/massey"));
    }

    @Test
    void unknownSlug_404s() throws Exception {
        mockMvc.perform(get("/rankings/elo"))
                .andExpect(status().isNotFound());
    }

    @Test
    void legacyComprehensiveRedirect_stillWins_overSlugPattern() throws Exception {
        mockMvc.perform(get("/rankings/comprehensive"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/rankings"));
    }

    @Test
    void rankingsOverview_hasNoTabsAndLinksModels() throws Exception {
        mockMvc.perform(get("/rankings"))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("comp-tab-strip"))))
                .andExpect(content().string(not(containsString("Scatter Matrix"))))
                .andExpect(content().string(containsString("/stats/correlation")))
                .andExpect(content().string(containsString("/rankings/massey")));
    }

    @Test
    void retiredTabEndpoints_noLongerServe() throws Exception {
        mockMvc.perform(get("/rankings/2025/model-view"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/rankings/2025/scatter-matrix"))
                .andExpect(status().isNotFound());
    }

    @Test
    void rankingsTableFragment_stillServes() throws Exception {
        mockMvc.perform(get("/rankings/2025/table"))
                .andExpect(status().isOk());
    }
}
