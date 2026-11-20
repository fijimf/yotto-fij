package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ChartDataControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;

    Season season; Conference acc; Team duke;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);
        acc = new Conference(); acc.setName("ACC"); acc.setAbbreviation("ACC"); acc.setEspnId("acc"); conferenceRepo.save(acc);
        duke = new Team(); duke.setName("Duke"); duke.setAbbreviation("DUKE"); duke.setEspnId("duke"); duke.setActive(true); teamRepo.save(duke);
        ConferenceMembership m = new ConferenceMembership(); m.setTeam(duke); m.setConference(acc); m.setSeason(season); membershipRepo.save(m);
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(duke); s.setSeason(season); s.setModelType("MASSEY"); s.setSnapshotDate(LocalDate.of(2025, 1, 5));
        s.setRating(12.0); s.setRank(1); s.setGamesPlayed(10); s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    @Test
    void ratingRiver_returnsJsonPayload() throws Exception {
        mockMvc.perform(get("/api/charts/rating-river").param("conferenceId", acc.getId().toString()).param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.model").value("massey"))
                .andExpect(jsonPath("$.dates[0]").value("2025-01-05"))
                .andExpect(jsonPath("$.teams[0].abbr").value("DUKE"))
                .andExpect(jsonPath("$.teams[0].rank[0]").value(1));
    }

    @Test
    void ratingRiver_unknownModel_is400_unknownConference_is404() throws Exception {
        mockMvc.perform(get("/api/charts/rating-river").param("conferenceId", acc.getId().toString()).param("year", "2025").param("model", "elo"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/charts/rating-river").param("conferenceId", "999999").param("year", "2025"))
                .andExpect(status().isNotFound());
    }

    @Test
    void fingerprints_apiAndPage() throws Exception {
        mockMvc.perform(get("/api/charts/fingerprints").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2025))
                .andExpect(jsonPath("$.axes[0].key").value("efg"))
                .andExpect(jsonPath("$.teams").isEmpty());
        mockMvc.perform(get("/api/charts/fingerprints").param("year", "1999"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/seasons/2025/stats/fingerprints"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-fingerprints")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data-year=\"2025\"")));
        mockMvc.perform(get("/stats/fingerprints"))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void efficiencyLandscape_returnsEmptyPayloadWithoutAdjRatings() throws Exception {
        mockMvc.perform(get("/api/charts/efficiency-landscape").param("year", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.year").value(2025))
                .andExpect(jsonPath("$.dates").isEmpty())
                .andExpect(jsonPath("$.teams").isEmpty());
    }
}
