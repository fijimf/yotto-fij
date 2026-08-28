package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamSeasonStatSnapshot;
import com.yotto.basketball.entity.TeamStatSnapshot;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.TeamSeasonStatSnapshotRepository;
import com.yotto.basketball.repository.TeamStatSnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the pages that adopted the Phase-1 shared fragments
 * (fragments/controls.html season selector + as-of date control) and asserts
 * the fragment markup actually reaches the HTML. Guards against the
 * MockMvc-invisible Thymeleaf failure modes (fragment param mismatch renders
 * nothing rather than erroring).
 */
@AutoConfigureMockMvc
class SharedFragmentsRenderTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamSeasonStatSnapshotRepository wideSnapshotRepo;
    @Autowired TeamStatSnapshotRepository statSnapshotRepo;

    static final LocalDate SNAP = LocalDate.of(2025, 1, 24);

    Season season;
    Team team;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        team = new Team();
        team.setName("Alabama");
        team.setEspnId("TA");
        team.setAbbreviation("ALA");
        team.setActive(true);
        teamRepo.save(team);
    }

    @Test
    void seasonStats_rendersSharedSeasonSelectAndAsOfDate() throws Exception {
        TeamSeasonStatSnapshot s = new TeamSeasonStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(SNAP);
        s.setGamesPlayed(5);
        s.setWinPct(0.6);
        wideSnapshotRepo.save(s);

        mockMvc.perform(get("/seasons/{year}/stats", 2025))
                .andExpect(status().isOk())
                // shared season selector: template placeholder + the season option
                .andExpect(content().string(containsString("data-url-template=\"/seasons/{year}/stats\"")))
                // shared as-of date control with its HTMX wiring
                .andExpect(content().string(containsString("id=\"as-of-date\"")))
                .andExpect(content().string(containsString("hx-target=\"#stats-table-container\"")))
                .andExpect(content().string(containsString("id=\"as-of-loading\"")));
    }

    @Test
    void statDetail_rendersSharedSeasonSelect() throws Exception {
        TeamStatSnapshot s = new TeamStatSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setSnapshotDate(SNAP);
        s.setStatName("efg_pct");
        s.setValue(0.52);
        s.setGamesPlayed(5);
        s.setRank(1);
        statSnapshotRepo.save(s);

        mockMvc.perform(get("/seasons/{year}/stats/{statName}", 2025, "efg_pct"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("data-url-template=\"/seasons/{year}/stats/efg_pct\"")));
    }

    @Test
    void chartThemeScript_isLoadedInLayout() throws Exception {
        // The resource chain rewrites asset URLs to content-hashed versions
        // (/js/chart-theme-<md5>.js), so match the stable prefix only.
        mockMvc.perform(get("/seasons/{year}/stats", 2025))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/js/chart-theme")));
    }
}
