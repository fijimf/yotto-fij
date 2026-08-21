package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class PredictionsPageControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired GameRepository gameRepo;

    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Season season;
    Team home, away;

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        home = mkTeam("Duke", "DUKE");
        away = mkTeam("UNC", "UNC");
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(abbr);
        t.setAbbreviation(abbr);
        t.setActive(true);
        return teamRepo.save(t);
    }

    /* The /predictions page was retired in the menu reorganization (spec OQ-3):
       superseded by the per-model schedule pages, with a 301 covered by
       ModelHubControllerTest.predictions_301sToCompare_whenNoDefaultMlModel. */

    // ── GET /models/matchup ───────────────────────────────────────────────────

    @Test
    void matchup_returnsActiveTeamsSortedAlphabetically() throws Exception {
        // Make sure mkTeam("Auburn") would appear before "Duke" if active and sorted
        mkTeam("Auburn", "AUB");

        mockMvc.perform(get("/models/matchup"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/matchup"))
                .andExpect(model().attribute("currentPage", "matchup"))
                .andExpect(model().attribute("teams",
                        org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.hasProperty("name", org.hamcrest.Matchers.equalTo("Auburn")),
                                org.hamcrest.Matchers.hasProperty("name", org.hamcrest.Matchers.equalTo("Duke")),
                                org.hamcrest.Matchers.hasProperty("name", org.hamcrest.Matchers.equalTo("UNC")))));
    }

    @Test
    void matchup_excludesInactiveTeams() throws Exception {
        Team inactive = mkTeam("Inactive", "INA");
        inactive.setActive(false);
        teamRepo.save(inactive);

        mockMvc.perform(get("/models/matchup"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("teams", org.hamcrest.Matchers.hasSize(2)))
                .andExpect(model().attribute("teams", org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.hasProperty("name",
                                org.hamcrest.Matchers.not(org.hamcrest.Matchers.equalTo("Inactive"))))));
    }

    // ── GET /predictions/matchup/result ───────────────────────────────────────

    @Test
    void matchupResult_sameTeam_returnsErrorAttribute() throws Exception {
        mockMvc.perform(get("/models/matchup/result")
                        .param("homeTeamId", home.getId().toString())
                        .param("awayTeamId", home.getId().toString())
                        .param("date", "2025-01-15"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/matchup-result :: matchup-result"))
                .andExpect(model().attribute("error",
                        org.hamcrest.Matchers.equalTo("Home and away teams must be different.")));
    }

    @Test
    void matchupResult_validMatchup_returnsResultAttribute() throws Exception {
        mockMvc.perform(get("/models/matchup/result")
                        .param("homeTeamId", home.getId().toString())
                        .param("awayTeamId", away.getId().toString())
                        .param("date", "2025-01-15")
                        .param("neutral", "false"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/matchup-result :: matchup-result"))
                .andExpect(model().attributeExists("result"))
                .andExpect(model().attributeDoesNotExist("error"));
    }

    @Test
    void matchupResult_predictMatchupThrows_setsErrorAttribute() throws Exception {
        // Unknown team id triggers EntityNotFoundException → caught and surfaced as error
        mockMvc.perform(get("/models/matchup/result")
                        .param("homeTeamId", "999999")
                        .param("awayTeamId", away.getId().toString())
                        .param("date", "2025-01-15"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("error",
                        org.hamcrest.Matchers.startsWith("Could not generate prediction:")));
    }
}
