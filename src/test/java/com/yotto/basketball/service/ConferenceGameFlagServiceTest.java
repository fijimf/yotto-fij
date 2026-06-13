package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ConferenceGameFlagServiceTest extends BaseIntegrationTest {

    @Autowired ConferenceGameFlagService service;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired GameRepository gameRepo;

    Season season;
    Conference sec, acc;
    Team alabama, auburn, clemson;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        sec = mkConference("SEC", "sec1");
        acc = mkConference("ACC", "acc1");

        alabama = mkTeam("Alabama", "T1");
        auburn = mkTeam("Auburn", "T2");
        clemson = mkTeam("Clemson", "T3");

        enroll(alabama, sec);
        enroll(auburn, sec);
        enroll(clemson, acc);
    }

    private Conference mkConference(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name);
        c.setEspnId(espnId);
        return conferenceRepo.save(c);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
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

    private Game mkGame(Team home, Team away, Game.GameStatus status, Boolean existingFlag) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setSeason(season);
        g.setGameDate(LocalDateTime.of(2025, 1, 15, 20, 0));
        g.setStatus(status);
        g.setConferenceGame(existingFlag);
        return gameRepo.save(g);
    }

    @Test
    void sameConference_flaggedTrue() {
        Game g = mkGame(alabama, auburn, Game.GameStatus.FINAL, null);

        service.updateForSeason(2025);

        assertThat(gameRepo.findById(g.getId()).orElseThrow().getConferenceGame()).isTrue();
    }

    @Test
    void crossConference_flaggedFalse() {
        Game g = mkGame(alabama, clemson, Game.GameStatus.FINAL, null);

        service.updateForSeason(2025);

        assertThat(gameRepo.findById(g.getId()).orElseThrow().getConferenceGame()).isFalse();
    }

    @Test
    void teamWithoutMembership_flaggedFalse() {
        Team independent = mkTeam("Independent", "T9"); // no enrollment
        Game g = mkGame(alabama, independent, Game.GameStatus.FINAL, null);

        service.updateForSeason(2025);

        assertThat(gameRepo.findById(g.getId()).orElseThrow().getConferenceGame()).isFalse();
    }

    @Test
    void staleFlag_isCorrected() {
        Game g = mkGame(alabama, auburn, Game.GameStatus.FINAL, false); // wrong

        service.updateForSeason(2025);

        assertThat(gameRepo.findById(g.getId()).orElseThrow().getConferenceGame()).isTrue();
    }

    @Test
    void scheduledGames_areFlaggedToo() {
        Game g = mkGame(alabama, auburn, Game.GameStatus.SCHEDULED, null);

        service.updateForSeason(2025);

        assertThat(gameRepo.findById(g.getId()).orElseThrow().getConferenceGame()).isTrue();
    }

    @Test
    void unknownSeason_skipsGracefully() {
        service.updateForSeason(9999); // must not throw
    }
}
