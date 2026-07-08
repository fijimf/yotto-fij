package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.ScrapeBatch;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.repository.BettingOddsRepository;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.SeasonPopulationStatRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.SeasonStatisticsRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.scraping.AsyncScrapeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * AsyncScrapeService is @MockBean so the test can assert that admin POST
 * routes correctly invoke the right async kickoff. @DirtiesContext is needed
 * because @MockBean swaps a singleton bean.
 */
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired GameRepository gameRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;

    @MockBean AsyncScrapeService asyncScrapeService;

    @BeforeEach
    void setUp() {
        // FK-safe delete order — see project_test_cleanup_order memory.
    }

    // ── GET /admin ────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboard_emptyState_returnsViewWithEmptyModelLists() throws Exception {
        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attribute("seasons", org.hamcrest.Matchers.hasSize(0)))
                .andExpect(model().attributeExists("healthByYear"))
                .andExpect(model().attributeExists("entries"))
                .andExpect(model().attributeExists("automation"))
                .andExpect(model().attributeExists("mlStatus"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void dashboard_seasonsAreSortedDescendingByYear() throws Exception {
        mkSeason(2024);
        mkSeason(2026);
        mkSeason(2025);

        mockMvc.perform(get("/admin"))
                .andExpect(status().isOk())
                .andExpect(model().attribute("seasons", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(model().attribute("seasons",
                        org.hamcrest.Matchers.contains(
                                org.hamcrest.Matchers.hasProperty("year", org.hamcrest.Matchers.equalTo(2026)),
                                org.hamcrest.Matchers.hasProperty("year", org.hamcrest.Matchers.equalTo(2025)),
                                org.hamcrest.Matchers.hasProperty("year", org.hamcrest.Matchers.equalTo(2024)))));
    }

    // ── POST /admin/seasons ───────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void addSeason_validYear_savesSeasonAndKicksOffScrape() throws Exception {
        mockMvc.perform(post("/admin/seasons").with(csrf())
                        .param("year", "2027"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attributeExists("success"));

        Season saved = seasonRepo.findByYear(2027).orElseThrow();
        assertThat(saved.getStartDate()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(saved.getEndDate()).isEqualTo(LocalDate.of(2027, 4, 30));
        assertThat(saved.getAutoRefresh()).isTrue();
        verify(asyncScrapeService).scrapeFullSeasonAsync(2027, ScrapeBatch.Source.AUTO_INITIALIZE);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addSeason_initializeFalse_doesNotTriggerScrape() throws Exception {
        mockMvc.perform(post("/admin/seasons").with(csrf())
                        .param("year", "2027")
                        .param("initialize", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        assertThat(seasonRepo.findByYear(2027)).isPresent();
        verifyNoInteractions(asyncScrapeService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addSeason_outOfRangeYear_rejectedWithError() throws Exception {
        mockMvc.perform(post("/admin/seasons").with(csrf())
                        .param("year", "1999"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("error", "Year must be between 2000 and 2099"));

        assertThat(seasonRepo.findByYear(1999)).isEmpty();
        verifyNoInteractions(asyncScrapeService);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void addSeason_duplicateYear_rejectedWithError() throws Exception {
        mkSeason(2025);

        mockMvc.perform(post("/admin/seasons").with(csrf())
                        .param("year", "2025"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("error", "Season 2025 already exists"));

        verifyNoInteractions(asyncScrapeService);
    }

    // ── DELETE /admin/seasons/{year} ──────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSeason_existing_removesRow() throws Exception {
        mkSeason(2025);

        mockMvc.perform(delete("/admin/seasons/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("success", "Season 2025 removed"));

        assertThat(seasonRepo.findByYear(2025)).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void deleteSeason_unknown_returnsErrorFlash() throws Exception {
        mockMvc.perform(delete("/admin/seasons/{year}", 9999).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"))
                .andExpect(flash().attribute("error", "Season 9999 not found"));
    }

    // ── Auto-refresh toggle ───────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void toggleAutoRefresh_existing_updatesFlag() throws Exception {
        Season s = mkSeason(2025);
        s.setAutoRefresh(false);
        seasonRepo.save(s);

        mockMvc.perform(post("/admin/seasons/{year}/auto-refresh", 2025).with(csrf())
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));

        assertThat(seasonRepo.findByYear(2025).orElseThrow().getAutoRefresh()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void toggleAutoRefresh_unknown_errorFlash() throws Exception {
        mockMvc.perform(post("/admin/seasons/{year}/auto-refresh", 9999).with(csrf())
                        .param("enabled", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Season 9999 not found"));
    }

    // ── Scrape kickoff endpoints ──────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void scrapeFullSeason_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/full/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin"));
        verify(asyncScrapeService).scrapeFullSeasonAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void scrapeCurrentSeason_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/current/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).scrapeCurrentSeasonAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void scrapeTeams_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/teams/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).scrapeTeamsAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void backfillOdds_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/odds/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).backfillOddsAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void backfillGameStats_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/game-stats/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).backfillGameStatsAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void calculateStats_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/stats/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).calculateStatsAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void calculateTimeSeries_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/scrape/timeseries/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).calculateTimeSeriesAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void calculatePowerRatings_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/power-ratings/{year}", 2025).with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).calculatePowerRatingsAsync(2025);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void runScheduledCycleNow_callsAsyncService() throws Exception {
        mockMvc.perform(post("/admin/automation/run-now").with(csrf()))
                .andExpect(status().is3xxRedirection());
        verify(asyncScrapeService).runScheduledCycleAsync();
    }

    // ── Fragment endpoints ────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void scrapeHistoryFragment_returnsFragmentView() throws Exception {
        mockMvc.perform(get("/admin/scrape-history"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fragments/scrape-history :: scrape-table"))
                .andExpect(model().attributeExists("entries"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void mlStatusFragment_returnsFragmentView() throws Exception {
        mockMvc.perform(get("/admin/ml/status"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/fragments/ml-status :: ml-status-card"))
                .andExpect(model().attributeExists("mlStatus"));
    }

    // ── Authorization ─────────────────────────────────────────────────────────

    @Test
    void unauthenticatedDashboard_redirectsToLogin() throws Exception {
        mockMvc.perform(get("/admin").accept(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    private Season mkSeason(int year) {
        Season s = new Season();
        s.setYear(year);
        s.setStartDate(LocalDate.of(year - 1, 11, 1));
        s.setEndDate(LocalDate.of(year, 4, 30));
        return seasonRepo.save(s);
    }
}
