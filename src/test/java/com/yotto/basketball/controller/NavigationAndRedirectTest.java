package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Menu-reorganization (spec §2–§4) coverage: the dropdown nav renders for every
 * auth state, moved URLs 301 to their new homes, and the bracket page carries
 * year tabs for tournament seasons only.
 */
@AutoConfigureMockMvc
class NavigationAndRedirectTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;

    Season s2025;
    Season s2024;

    @BeforeEach
    void setUp() {
        s2025 = mkSeason(2025);
        s2024 = mkSeason(2024);
    }

    private Season mkSeason(int year) {
        Season s = new Season();
        s.setYear(year);
        s.setStartDate(LocalDate.of(year - 1, 11, 1));
        s.setEndDate(LocalDate.of(year, 4, 30));
        return seasonRepo.save(s);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkNcaaGame(Season season, Team home, Team away) {
        Game g = new Game();
        g.setSeason(season);
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setGameDate(LocalDateTime.of(season.getYear(), 3, 20, 19, 0));
        g.setStatus(Game.GameStatus.FINAL);
        g.setEspnId("ncaa-" + season.getYear() + "-" + home.getEspnId());
        g.setHomeScore(70);
        g.setAwayScore(60);
        g.setTournamentType(Game.TournamentType.NCAA_TOURNAMENT);
        g.setTournamentRound("1st Round");
        g.setTournamentRegion("East");
        gameRepo.save(g);
    }

    // ── Nav structure per auth state ──────────────────────────────────────────

    @Test
    void nav_anonymous_hasSectionDropdownsAndSignIn() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Games<span class=\"nav__dropdown-caret\"")))
                .andExpect(content().string(containsString("Statistics<span class=\"nav__dropdown-caret\"")))
                .andExpect(content().string(containsString("Power Rankings<span class=\"nav__dropdown-caret\"")))
                .andExpect(content().string(containsString("Models<span class=\"nav__dropdown-caret\"")))
                .andExpect(content().string(containsString("href=\"/models/compare\"")))
                .andExpect(content().string(containsString("href=\"/models/matchup\"")))
                .andExpect(content().string(containsString("href=\"/bracket\"")))
                .andExpect(content().string(containsString("href=\"/seasons/stats\"")))
                .andExpect(content().string(containsString(">Sign in<")))
                .andExpect(content().string(not(containsString("href=\"/admin\""))));
    }

    @Test
    @WithMockUser(username = "fan", roles = "USER")
    void nav_user_hasAccountMenuNoAdmin() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("fan")))
                .andExpect(content().string(containsString("href=\"/account\"")))
                .andExpect(content().string(not(containsString("href=\"/admin\""))))
                .andExpect(content().string(not(containsString(">Sign in<"))));
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    void nav_admin_hasAdminLink() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/admin\"")));
    }

    @Test
    void nav_activeSection_highlightsGamesOnGamesPage() throws Exception {
        mockMvc.perform(get("/games"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("nav__link--active")));
    }

    // ── Legacy redirects (spec §2.3) ──────────────────────────────────────────

    @Test
    void performance_oldUrl_301sToModelsCompare_preservingQuery() throws Exception {
        mockMvc.perform(get("/predictions/performance").queryParam("year", "2025").queryParam("segment", "ncaa"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", containsString("/models/compare?year=2025&segment=ncaa")));
    }

    @Test
    void matchup_oldUrl_301sToModelsMatchup() throws Exception {
        mockMvc.perform(get("/predictions/matchup"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", containsString("/models/matchup")));
    }

    @Test
    void modelsCompare_rendersDirectly() throws Exception {
        mockMvc.perform(get("/models/compare"))
                .andExpect(status().isOk());
    }

    @Test
    void modelsMatchup_rendersDirectly() throws Exception {
        mockMvc.perform(get("/models/matchup"))
                .andExpect(status().isOk());
    }

    // ── Bracket entry point + year tabs (spec §4.2) ───────────────────────────

    @Test
    void bracket_redirectsToMostRecentTournamentSeason() throws Exception {
        Team a = mkTeam("Alpha", "A1");
        Team b = mkTeam("Beta", "B1");
        mkNcaaGame(s2024, a, b); // only 2024 has tournament games; 2025 exists but has none

        mockMvc.perform(get("/bracket"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/seasons/2024/bracket"));
    }

    @Test
    void bracketPage_yearTabs_listOnlyTournamentSeasonsPlusCurrent() throws Exception {
        Team a = mkTeam("Alpha", "A1");
        Team b = mkTeam("Beta", "B1");
        mkNcaaGame(s2024, a, b);

        // 2024 has a bracket: tab for 2024, none for 2025
        mockMvc.perform(get("/seasons/2024/bracket"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tab-strip__tab--active")))
                .andExpect(content().string(containsString("href=\"/seasons/2024/bracket\"")))
                .andExpect(content().string(not(containsString("href=\"/seasons/2025/bracket\""))));

        // Viewing a season with no tournament games still shows its own (active) tab
        mockMvc.perform(get("/seasons/2025/bracket"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/seasons/2025/bracket\"")))
                .andExpect(content().string(containsString("href=\"/seasons/2024/bracket\"")));
    }
}
