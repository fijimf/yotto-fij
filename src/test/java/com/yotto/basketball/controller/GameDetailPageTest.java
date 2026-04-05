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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class GameDetailPageTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;

    Long gameId;

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

        Conference conf = new Conference();
        conf.setName("ACC");
        conf.setAbbreviation("ACC");
        conf.setEspnId("acc-id");
        conferenceRepo.save(conf);

        Team home = mkTeam("Duke", "DUKE");
        Team away = mkTeam("UNC", "UNC");

        Season season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        SeasonStatistics homeStats = new SeasonStatistics();
        homeStats.setTeam(home);
        homeStats.setSeason(season);
        homeStats.setConference(conf);
        homeStats.setWins(15);
        homeStats.setLosses(5);
        homeStats.setCalcWins(15);
        homeStats.setCalcLosses(5);
        homeStats.setCalcPointsFor(1050);
        homeStats.setCalcPointsAgainst(900);
        statsRepo.save(homeStats);

        SeasonStatistics awayStats = new SeasonStatistics();
        awayStats.setTeam(away);
        awayStats.setSeason(season);
        awayStats.setConference(conf);
        awayStats.setWins(12);
        awayStats.setLosses(8);
        awayStats.setCalcWins(12);
        awayStats.setCalcLosses(8);
        awayStats.setCalcPointsFor(980);
        awayStats.setCalcPointsAgainst(940);
        statsRepo.save(awayStats);

        Game game = new Game();
        game.setHomeTeam(home);
        game.setAwayTeam(away);
        game.setStatus(Game.GameStatus.FINAL);
        game.setHomeScore(75);
        game.setAwayScore(68);
        game.setNeutralSite(false);
        game.setConferenceGame(true);
        game.setSeason(season);
        game.setGameDate(LocalDateTime.of(2025, 2, 15, 19, 0));
        gameRepo.save(game);
        gameId = game.getId();
    }

    @Test
    void gameDetailPage_returns200() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(view().name("pages/game-detail"));
    }

    @Test
    void gameDetailPage_hasRequiredModelAttributes() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("homeH2HWins"))
                .andExpect(model().attributeExists("awayH2HWins"))
                .andExpect(model().attributeExists("lastMeetings"))
                .andExpect(model().attributeExists("homeStats"))
                .andExpect(model().attributeExists("awayStats"))
                .andExpect(model().attributeExists("homeNeutralWins"))
                .andExpect(model().attributeExists("awayNeutralWins"))
                .andExpect(model().attributeExists("homeLast5Wins"))
                .andExpect(model().attributeExists("awayLast5Wins"))
                .andExpect(model().attributeExists("chartDataJson"));
    }

    @Test
    void gameDetailPage_conferenceName_setForConferenceGame() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(model().attribute("conferenceName", "ACC"));
    }

    @Test
    void gameDetailPage_h2hWins_zeroWithNoPriorMeetings() throws Exception {
        mockMvc.perform(get("/games/" + gameId))
                .andExpect(status().isOk())
                .andExpect(model().attribute("homeH2HWins", 0L))
                .andExpect(model().attribute("awayH2HWins", 0L));
    }

    private Team mkTeam(String name, String abbr) {
        Team t = new Team();
        t.setName(name);
        t.setAbbreviation(abbr);
        t.setEspnId(abbr + "-test-id");
        t.setActive(true);
        return teamRepo.save(t);
    }
}
