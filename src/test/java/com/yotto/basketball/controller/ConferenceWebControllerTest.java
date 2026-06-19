package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import com.yotto.basketball.service.MasseyRatingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class ConferenceWebControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired GameRepository gameRepo;

    Season season;
    Conference sec, big;
    Team a, b, c, d;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = mkConf("SEC", "sec-1");
        big = mkConf("Big Ten", "big-1");

        a = mkTeam("Alabama", "a");
        b = mkTeam("Auburn", "b");
        c = mkTeam("Purdue", "c");
        d = mkTeam("Iowa", "d");

        enroll(a, sec); enroll(b, sec);
        enroll(c, big); enroll(d, big);

        mkStats(a, sec, 20, 5, 14, 4, 1);
        mkStats(b, sec, 10, 15, 8, 10, 2);
        mkStats(c, big, 25, 5, 16, 4, 1);
        mkStats(d, big, 8, 20, 5, 13, 2);

        rate(a, 20.0, 3); rate(b, 10.0, 12);
        rate(c, 30.0, 1); rate(d, 5.0, 30);
    }

    // ── GET /conferences ──

    @Test
    void index_returnsPageWithRankedConferences() throws Exception {
        MvcResult res = mockMvc.perform(get("/conferences"))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/conferences"))
                .andExpect(model().attribute("currentPage", "conferences"))
                .andExpect(model().attribute("seasonYear", 2025))
                .andExpect(model().attribute("conferenceCount", 2))
                .andReturn();

        @SuppressWarnings("unchecked")
        List<ConferenceWebController.ConferenceSummary> rows =
                (List<ConferenceWebController.ConferenceSummary>)
                        res.getModelAndView().getModel().get("conferences");
        // Big Ten (avg 17.5) ranks ahead of SEC (avg 15.0)
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Big Ten");
        assertThat(rows.get(0).conferenceRank()).isEqualTo(1);
        assertThat(rows.get(1).name()).isEqualTo("SEC");
    }

    // ── GET /conferences/{id} ──

    @Test
    void detail_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/conferences/{id}", 999999L))
                .andExpect(status().isNotFound());
    }

    @Test
    void detail_returnsStandingsSortedByConferenceStanding() throws Exception {
        MvcResult res = mockMvc.perform(get("/conferences/{id}", sec.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/conference-detail"))
                .andExpect(model().attribute("currentSeasonYear", 2025))
                .andReturn();

        ConferenceWebController.ConferenceDetail detail = (ConferenceWebController.ConferenceDetail)
                res.getModelAndView().getModel().get("detail");
        assertThat(detail).isNotNull();
        assertThat(detail.standings()).hasSize(2);
        // Alabama is conferenceStanding 1
        assertThat(detail.standings().get(0).teamName()).isEqualTo("Alabama");
        assertThat(detail.standings().get(0).standing()).isEqualTo(1);
        assertThat(detail.standings().get(0).powerRankDisplay()).isEqualTo("#3");
        // No tournament / NCAA games seeded
        assertThat(detail.tournament()).isNull();
        assertThat(detail.ncaa()).isNull();
    }

    @Test
    void detail_buildsConferenceTournamentWithChampion() throws Exception {
        // Semifinal + Final, all SEC teams
        Game semi = mkTournGame(a, b, 80, 70, Game.TournamentType.CONFERENCE_TOURNAMENT,
                "Semifinal", LocalDate.of(2025, 3, 14));
        Game finalG = mkTournGame(a, b, 75, 60, Game.TournamentType.CONFERENCE_TOURNAMENT,
                "Final", LocalDate.of(2025, 3, 16));

        MvcResult res = mockMvc.perform(get("/conferences/{id}", sec.getId())).andReturn();
        ConferenceWebController.ConferenceDetail detail = (ConferenceWebController.ConferenceDetail)
                res.getModelAndView().getModel().get("detail");

        assertThat(detail.tournament()).isNotNull();
        assertThat(detail.tournament().groups()).hasSize(2);
        // chronological: Semifinal first, Final second
        assertThat(detail.tournament().groups().get(0).label()).isEqualTo("Semifinal");
        assertThat(detail.tournament().groups().get(1).label()).isEqualTo("Final");
        assertThat(detail.tournament().championName()).isEqualTo("Alabama");
    }

    @Test
    void detail_buildsNcaaSummaryWithBidsAndChampion() throws Exception {
        // Alabama (SEC) wins the National Championship over Purdue (Big Ten)
        mkTournGame(a, c, 85, 80, Game.TournamentType.NCAA_TOURNAMENT,
                "National Championship", LocalDate.of(2025, 4, 7));

        MvcResult res = mockMvc.perform(get("/conferences/{id}", sec.getId())).andReturn();
        ConferenceWebController.ConferenceDetail detail = (ConferenceWebController.ConferenceDetail)
                res.getModelAndView().getModel().get("detail");

        assertThat(detail.ncaa()).isNotNull();
        assertThat(detail.ncaa().bidCount()).isEqualTo(1); // only SEC member Alabama
        assertThat(detail.ncaa().wins()).isEqualTo(1);
        assertThat(detail.ncaa().champion()).isEqualTo("Alabama");
    }

    // ── GET /conferences/{id}/season/{year} ──

    @Test
    void seasonFragment_returnsPanelView() throws Exception {
        mockMvc.perform(get("/conferences/{id}/season/{year}", sec.getId(), 2025))
                .andExpect(status().isOk())
                .andExpect(view().name("fragments/conference-season :: panel"))
                .andExpect(model().attributeExists("detail"));
    }

    @Test
    void seasonFragment_unknownYear_returns404() throws Exception {
        mockMvc.perform(get("/conferences/{id}/season/{year}", sec.getId(), 9999))
                .andExpect(status().isNotFound());
    }

    // ── helpers ──

    private Conference mkConf(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name);
        c.setAbbreviation(name);
        c.setEspnId(espnId);
        return conferenceRepo.save(c);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void enroll(Team team, Conference conf) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(conf);
        m.setSeason(season);
        membershipRepo.save(m);
    }

    private void mkStats(Team team, Conference conf, int w, int l, int cw, int cl, int standing) {
        SeasonStatistics ss = new SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(conf);
        ss.setCalcWins(w);
        ss.setCalcLosses(l);
        ss.setCalcConferenceWins(cw);
        ss.setCalcConferenceLosses(cl);
        ss.setConferenceStanding(standing);
        statsRepo.save(ss);
    }

    private void rate(Team team, double rating, int rank) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(MasseyRatingService.MODEL_TYPE);
        s.setSnapshotDate(LocalDate.of(2025, 3, 1));
        s.setRating(rating);
        s.setRank(rank);
        s.setGamesPlayed(20);
        s.setCalculatedAt(java.time.LocalDateTime.now());
        ratingRepo.save(s);
    }

    private Game mkTournGame(Team home, Team away, int hs, int as,
                             Game.TournamentType type, String round, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(Game.GameStatus.FINAL);
        g.setSeason(season);
        g.setTournamentType(type);
        g.setTournamentRound(round);
        g.setNeutralSite(true);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }
}
