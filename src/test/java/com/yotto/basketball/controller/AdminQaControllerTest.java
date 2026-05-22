package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class AdminQaControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired NonD1GameObservationRepository nonD1Repo;

    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired GameRepository gameRepo;

    Season season;
    Conference sec;
    Team teamA, teamB;

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = new Conference();
        sec.setName("SEC");
        sec.setAbbreviation("SEC");
        sec.setEspnId("sec-1");
        conferenceRepo.save(sec);

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private SeasonStatistics mkStatsTying(Team team, int wins, int losses) {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(sec);
        ss.setWins(wins);
        ss.setLosses(losses);
        ss.setCalcWins(wins);
        ss.setCalcLosses(losses);
        ss.setCalcLastUpdated(LocalDateTime.now()); // makes hasCalcData() true
        return statsRepo.save(ss);
    }

    private NonD1GameObservation mkNonD1Win(Team d1Team) {
        NonD1GameObservation obs = new NonD1GameObservation();
        obs.setEspnGameId("nd1-" + System.nanoTime());
        obs.setSeasonYear(2025);
        obs.setGameDateUtc(LocalDateTime.of(2025, 1, 5, 19, 0));
        obs.setScrapeDate(LocalDate.of(2025, 1, 5));
        obs.setHomeEspnId(d1Team.getEspnId());
        obs.setAwayEspnId("999999");
        obs.setFirstSeenAt(LocalDateTime.now());
        obs.setLastSeenAt(LocalDateTime.now());
        obs.setD1Team(d1Team);
        obs.setNonD1EspnId("999999");
        obs.setD1WasHome(true);
        obs.setNeutralSite(false);
        obs.setD1Score(80);
        obs.setNonD1Score(60);
        obs.setGameStatus("FINAL");
        obs.setResult("W");
        obs.setUnknownTeamEspnIds("999999");
        return nonD1Repo.save(obs);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_noSeasons_returnsPageWithEmptyDefaults() throws Exception {
        seasonRepo.deleteAll();

        mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/scraping-qa"))
                .andExpect(model().attribute("selectedSeason", (Object) null))
                .andExpect(model().attribute("matchCount", 0))
                .andExpect(model().attribute("discrepancies", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_defaultsToMostRecentSeasonWhenNoQueryParam() throws Exception {
        Season older = new Season();
        older.setYear(2024);
        older.setStartDate(LocalDate.of(2023, 11, 1));
        older.setEndDate(LocalDate.of(2024, 4, 30));
        seasonRepo.save(older);

        mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedSeason", 2025));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_explicitSeasonParam_overridesDefault() throws Exception {
        Season older = new Season();
        older.setYear(2024);
        older.setStartDate(LocalDate.of(2023, 11, 1));
        older.setEndDate(LocalDate.of(2024, 4, 30));
        seasonRepo.save(older);

        mockMvc.perform(get("/admin/qa").param("season", "2024"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("selectedSeason", 2024));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_statsThatTieOut_countAsMatching() throws Exception {
        mkStatsTying(teamA, 20, 5);
        mkStatsTying(teamB, 15, 10);

        mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("matchCount", 2))
                .andExpect(model().attribute("discrepancies", org.hamcrest.Matchers.hasSize(0)));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_statsWithoutCalcData_countedAsNoCalc() throws Exception {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(teamA);
        ss.setSeason(season);
        ss.setConference(sec);
        ss.setWins(20);
        ss.setLosses(5);
        // No calcLastUpdated → hasCalcData() = false
        statsRepo.save(ss);

        mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("noCalcCount", 1))
                .andExpect(model().attribute("matchCount", 0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_calcWithoutScraped_countedAsNoScraped() throws Exception {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(teamA);
        ss.setSeason(season);
        ss.setConference(sec);
        ss.setCalcWins(20);
        ss.setCalcLosses(5);
        ss.setCalcLastUpdated(LocalDateTime.now());
        // No scraped wins/losses
        statsRepo.save(ss);

        mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("noScrapedCount", 1))
                .andExpect(model().attribute("matchCount", 0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_mismatchBetweenScrapedAndCalc_addedToDiscrepancies() throws Exception {
        // Scraped says 22-5, calc says 20-5 → 2-win mismatch, no non-DI to absorb it
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(teamA);
        ss.setSeason(season);
        ss.setConference(sec);
        ss.setWins(22);
        ss.setLosses(5);
        ss.setCalcWins(20);
        ss.setCalcLosses(5);
        ss.setCalcLastUpdated(LocalDateTime.now());
        statsRepo.save(ss);

        MvcResult res = mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andReturn();

        @SuppressWarnings("unchecked")
        List<AdminQaController.StatDiscrepancy> discrepancies =
                (List<AdminQaController.StatDiscrepancy>)
                        res.getModelAndView().getModel().get("discrepancies");
        assertThat(discrepancies).hasSize(1);
        AdminQaController.StatDiscrepancy d = discrepancies.get(0);
        assertThat(d.teamName()).isEqualTo("Alabama");
        assertThat(d.conferenceName()).isEqualTo("SEC");
        AdminQaController.StatDiff winsDiff = d.diffs().stream()
                .filter(diff -> "Wins".equals(diff.field())).findFirst().orElseThrow();
        assertThat(winsDiff.scraped()).isEqualTo(22);
        assertThat(winsDiff.calc()).isEqualTo(20);
        assertThat(winsDiff.expected()).isEqualTo(20);
        assertThat(winsDiff.delta()).isEqualTo(-2);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void qa_nonD1WinAbsorbsScrapedExtra_countedAsMatching() throws Exception {
        // Scraped = 21, calc = 20 (D-I only), one non-DI win → expected = 21 → tie-out.
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(teamA);
        ss.setSeason(season);
        ss.setConference(sec);
        ss.setWins(21);
        ss.setLosses(5);
        ss.setCalcWins(20);
        ss.setCalcLosses(5);
        ss.setCalcLastUpdated(LocalDateTime.now());
        statsRepo.save(ss);
        mkNonD1Win(teamA);

        mockMvc.perform(get("/admin/qa"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("matchCount", 1))
                .andExpect(model().attribute("discrepancies", org.hamcrest.Matchers.hasSize(0)));
    }
}
