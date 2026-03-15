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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
class ConferenceMembershipControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired GameRepository gameRepo;

    Team teamA, teamB;
    Conference sec, acc;
    Season season;

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

        teamA = mkTeam("Alabama", "TA");
        teamB = mkTeam("Auburn", "TB");

        sec = mkConference("SEC", "sec1");
        acc = mkConference("ACC", "acc1");

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);
    }

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Conference mkConference(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name);
        c.setEspnId(espnId);
        return conferenceRepo.save(c);
    }

    private ConferenceMembership enroll(Team team, Conference conf) {
        ConferenceMembership m = new ConferenceMembership();
        m.setTeam(team);
        m.setConference(conf);
        m.setSeason(season);
        return membershipRepo.save(m);
    }

    // ── POST /api/conference-memberships ──────────────────────────────────────

    @Test
    void create_returns201() throws Exception {
        mockMvc.perform(post("/api/conference-memberships")
                        .param("teamId", teamA.getId().toString())
                        .param("conferenceId", sec.getId().toString())
                        .param("seasonId", season.getId().toString()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists());
    }

    // ── GET /api/conference-memberships/{id} ──────────────────────────────────

    @Test
    void getById_returnsMembership() throws Exception {
        ConferenceMembership m = enroll(teamA, sec);

        mockMvc.perform(get("/api/conference-memberships/{id}", m.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(m.getId()));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/conference-memberships/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /api/conference-memberships/team/{teamId} ─────────────────────────

    @Test
    void getByTeam_returnsMemberships() throws Exception {
        enroll(teamA, sec);

        mockMvc.perform(get("/api/conference-memberships/team/{teamId}", teamA.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── GET /api/conference-memberships/team/{teamId}/season/{seasonId} ───────

    @Test
    void getByTeamAndSeason_returnsMembership() throws Exception {
        enroll(teamA, sec);

        mockMvc.perform(get("/api/conference-memberships/team/{teamId}/season/{seasonId}",
                        teamA.getId(), season.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").exists());
    }

    // ── GET /api/conference-memberships/team/{teamId}/current ─────────────────

    @Test
    void getCurrentMembership_returnsMembership() throws Exception {
        enroll(teamA, sec);

        mockMvc.perform(get("/api/conference-memberships/team/{teamId}/current", teamA.getId()))
                .andExpect(status().isOk());
    }

    @Test
    void getCurrentMembership_noMembership_returns404() throws Exception {
        mockMvc.perform(get("/api/conference-memberships/team/{teamId}/current", teamA.getId()))
                .andExpect(status().isNotFound());
    }

    // ── GET /api/conference-memberships/conference/{conferenceId} ─────────────

    @Test
    void getByConference_returnsMemberships() throws Exception {
        enroll(teamA, sec);
        enroll(teamB, sec);

        mockMvc.perform(get("/api/conference-memberships/conference/{conferenceId}", sec.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── GET /api/conference-memberships/conference/{confId}/season/{seasonId} ─

    @Test
    void getByConferenceAndSeason_returnsMembers() throws Exception {
        enroll(teamA, sec);

        mockMvc.perform(get("/api/conference-memberships/conference/{confId}/season/{seasonId}",
                        sec.getId(), season.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ── GET /api/conference-memberships/season/{seasonId} ─────────────────────

    @Test
    void getBySeason_returnsMemberships() throws Exception {
        enroll(teamA, sec);
        enroll(teamB, acc);

        mockMvc.perform(get("/api/conference-memberships/season/{seasonId}", season.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── PUT /api/conference-memberships/{id} ──────────────────────────────────

    @Test
    void update_changesConference() throws Exception {
        ConferenceMembership m = enroll(teamA, sec);

        mockMvc.perform(put("/api/conference-memberships/{id}", m.getId())
                        .param("conferenceId", acc.getId().toString()))
                .andExpect(status().isOk());
    }

    @Test
    void update_notFound_returns404() throws Exception {
        mockMvc.perform(put("/api/conference-memberships/999999")
                        .param("conferenceId", sec.getId().toString()))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/conference-memberships/{id} ───────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        ConferenceMembership m = enroll(teamA, sec);

        mockMvc.perform(delete("/api/conference-memberships/{id}", m.getId()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/conference-memberships/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
