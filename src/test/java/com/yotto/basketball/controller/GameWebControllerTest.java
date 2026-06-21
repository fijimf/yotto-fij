package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class GameWebControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired GameWebController controller;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;

    Season season;
    Team a, b;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2026);
        season.setStartDate(LocalDate.of(2025, 11, 1));
        season.setEndDate(LocalDate.of(2026, 4, 30));
        seasonRepo.save(season);

        a = mkTeam("Alabama", "a");
        b = mkTeam("Auburn", "b");
    }

    // ── Eastern-date windowing ──

    @Test
    void gamesPage_bucketsByEasternDate_notUtcDate() throws Exception {
        // 9:00 PM ET on 2026-01-15 is 2026-01-16 02:00 UTC. It must show on the 15th (ET), not the 16th.
        LocalDateTime utc = LocalDate.of(2026, 1, 15).atTime(21, 0)
                .atZone(ZoneId.of("America/New_York")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        mkGame(a, b, 80, 70, Game.GameStatus.FINAL, utc);

        MvcResult res = mockMvc.perform(get("/games").param("date", "2026-01-15"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/games"))
                .andExpect(model().attribute("currentPage", "games"))
                .andExpect(model().attribute("gameCount", 1))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<GameWebController.GameDayRow> rows =
                (List<GameWebController.GameDayRow>) res.getModelAndView().getModel().get("games");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).homeWon()).isTrue();

        // The same game is absent on the 16th.
        mockMvc.perform(get("/games").param("date", "2026-01-16"))
                .andExpect(model().attribute("gameCount", 0));
    }

    @Test
    void gamesPage_invalidDate_fallsBackToDefault_noError() throws Exception {
        mockMvc.perform(get("/games").param("date", "not-a-date"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/games"))
                .andExpect(model().attributeExists("date"));
    }

    // ── Fragment ──

    @Test
    void gamesOnFragment_returnsListView() throws Exception {
        mockMvc.perform(get("/games/on/{date}", "2026-01-15"))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/games-list :: games-list"))
                .andExpect(model().attributeExists("games"));
    }

    // ── Prev/next (adjacent day WITH games) ──

    @Test
    void prevNext_jumpToAdjacentDaysThatHaveGames() throws Exception {
        mkGame(a, b, 1, 2, Game.GameStatus.FINAL, easternNoonUtc(LocalDate.of(2026, 1, 10)));
        mkGame(a, b, 3, 4, Game.GameStatus.FINAL, easternNoonUtc(LocalDate.of(2026, 1, 20)));

        MvcResult res = mockMvc.perform(get("/games").param("date", "2026-01-15")).andReturn();
        assertThat(res.getModelAndView().getModel().get("prevDate")).isEqualTo(LocalDate.of(2026, 1, 10));
        assertThat(res.getModelAndView().getModel().get("nextDate")).isEqualTo(LocalDate.of(2026, 1, 20));
    }

    // ── Default-date logic (unit-level on the bean) ──

    @Test
    void resolveDefaultDate_outOfSeasonNonNovember_returnsSeasonFinale() {
        // Games Nov 2025 – Apr 2026; "today" (2026-06-21, June) is out of season → last game day.
        mkGame(a, b, 1, 2, Game.GameStatus.FINAL, easternNoonUtc(LocalDate.of(2025, 11, 5)));
        mkGame(a, b, 3, 4, Game.GameStatus.FINAL, easternNoonUtc(LocalDate.of(2026, 4, 6)));

        // Only valid to assert deterministically when the test runs out of season; guard on month.
        LocalDate today = LocalDate.now(ZoneId.of("America/New_York"));
        boolean inSeason = !today.isBefore(LocalDate.of(2025, 11, 5)) && !today.isAfter(LocalDate.of(2026, 4, 6));
        org.junit.jupiter.api.Assumptions.assumeFalse(inSeason, "skip when run during the seeded season");
        org.junit.jupiter.api.Assumptions.assumeTrue(today.getMonthValue() != 11, "skip in November");

        assertThat(controller.resolveDefaultDate()).isEqualTo(LocalDate.of(2026, 4, 6));
    }

    @Test
    void resolveDefaultDate_noSeasons_returnsToday() {
        seasonRepo.deleteAll();
        assertThat(controller.resolveDefaultDate()).isEqualTo(LocalDate.now(ZoneId.of("America/New_York")));
    }

    // ── helpers ──

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame(Team home, Team away, int hs, int as, Game.GameStatus st, LocalDateTime utc) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(st);
        g.setSeason(season);
        g.setGameDate(utc);
        return gameRepo.save(g);
    }

    /** Noon ET on the given date, expressed as the stored UTC instant. */
    private static LocalDateTime easternNoonUtc(LocalDate d) {
        return d.atTime(12, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
