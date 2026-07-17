package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class TeamControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired ConferenceRepository conferenceRepo;

    private Team mkTeam(String name) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId("espn-" + name);
        t.setMascot("Mascots");
        t.setAbbreviation(name.substring(0, Math.min(3, name.length())).toUpperCase());
        t.setSlug(name.toLowerCase());
        t.setColor("c41230");
        t.setAlternateColor("ffffff");
        t.setLogoUrl("https://example.com/" + name + ".png");
        t.setActive(true);
        return teamRepo.save(t);
    }

    // ── GET /api/teams ────────────────────────────────────────────────────────

    @Test
    void getAll_emptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsAllTeams() throws Exception {
        mkTeam("Alabama");
        mkTeam("Auburn");

        mockMvc.perform(get("/api/teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── POST /api/teams ───────────────────────────────────────────────────────

    @Test
    void create_returns201WithFullTeamRoundTrip() throws Exception {
        String body = """
                {
                  "name": "Kentucky",
                  "espnId": "espn-Kentucky",
                  "mascot": "Wildcats",
                  "abbreviation": "KEN",
                  "slug": "kentucky-wildcats",
                  "color": "0033a0",
                  "alternateColor": "ffffff",
                  "logoUrl": "https://example.com/Kentucky.png",
                  "active": true
                }
                """;

        mockMvc.perform(post("/api/teams")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Kentucky"))
                .andExpect(jsonPath("$.espnId").value("espn-Kentucky"))
                .andExpect(jsonPath("$.mascot").value("Wildcats"))
                .andExpect(jsonPath("$.abbreviation").value("KEN"))
                .andExpect(jsonPath("$.slug").value("kentucky-wildcats"))
                .andExpect(jsonPath("$.color").value("0033a0"))
                .andExpect(jsonPath("$.alternateColor").value("ffffff"))
                .andExpect(jsonPath("$.logoUrl").value("https://example.com/Kentucky.png"))
                .andExpect(jsonPath("$.active").value(true));

        Team saved = teamRepo.findByEspnId("espn-Kentucky").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getName()).isEqualTo("Kentucky");
        org.assertj.core.api.Assertions.assertThat(saved.getMascot()).isEqualTo("Wildcats");
        org.assertj.core.api.Assertions.assertThat(saved.getAbbreviation()).isEqualTo("KEN");
        org.assertj.core.api.Assertions.assertThat(saved.getSlug()).isEqualTo("kentucky-wildcats");
        org.assertj.core.api.Assertions.assertThat(saved.getColor()).isEqualTo("0033a0");
    }

    @Test
    void create_blankName_returns400WithValidationError() throws Exception {
        String body = """
                {"name": ""}
                """;

        mockMvc.perform(post("/api/teams")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Validation Failed"))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // ── GET /api/teams/{id} ───────────────────────────────────────────────────

    @Test
    void getById_returnsFullTeamShape() throws Exception {
        Team team = mkTeam("Florida");

        mockMvc.perform(get("/api/teams/{id}", team.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(team.getId()))
                .andExpect(jsonPath("$.name").value("Florida"))
                .andExpect(jsonPath("$.espnId").value("espn-Florida"))
                .andExpect(jsonPath("$.mascot").value("Mascots"))
                .andExpect(jsonPath("$.abbreviation").value("FLO"))
                .andExpect(jsonPath("$.slug").value("florida"))
                .andExpect(jsonPath("$.color").value("c41230"))
                .andExpect(jsonPath("$.alternateColor").value("ffffff"))
                .andExpect(jsonPath("$.logoUrl").value("https://example.com/Florida.png"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/teams/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    // ── GET /api/teams/search ─────────────────────────────────────────────────

    @Test
    void searchByName_returnsMatchingTeam() throws Exception {
        mkTeam("Tennessee");
        mkTeam("Texas");

        mockMvc.perform(get("/api/teams/search").param("name", "enne"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Tennessee"));
    }

    @Test
    void searchByName_caseInsensitive() throws Exception {
        mkTeam("Vanderbilt");

        mockMvc.perform(get("/api/teams/search").param("name", "VANDER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void searchByName_noResults_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/teams/search").param("name", "NoSuchTeam"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ── PUT /api/teams/{id} ───────────────────────────────────────────────────

    @Test
    void update_persistsNameNicknameMascotButPreservesScrapeManagedFields() throws Exception {
        // TeamService.update only mutates name/nickname/mascot. abbreviation,
        // slug, color, logoUrl, active are deliberately preserved — they come
        // from the team scraper and are not user-editable via this endpoint.
        Team team = mkTeam("OldName");
        String originalAbbreviation = team.getAbbreviation();
        String originalLogo = team.getLogoUrl();
        String originalColor = team.getColor();
        String originalSlug = team.getSlug();

        String body = """
                {
                  "name": "NewName",
                  "nickname": "Tigers Nickname",
                  "mascot": "Tigers",
                  "abbreviation": "NEW",
                  "color": "000000",
                  "logoUrl": "https://example.com/new.png",
                  "active": false
                }
                """;

        mockMvc.perform(put("/api/teams/{id}", team.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // Fields the service does update
                .andExpect(jsonPath("$.name").value("NewName"))
                .andExpect(jsonPath("$.nickname").value("Tigers Nickname"))
                .andExpect(jsonPath("$.mascot").value("Tigers"))
                // Fields the service preserves
                .andExpect(jsonPath("$.abbreviation").value(originalAbbreviation))
                .andExpect(jsonPath("$.color").value(originalColor))
                .andExpect(jsonPath("$.logoUrl").value(originalLogo))
                .andExpect(jsonPath("$.slug").value(originalSlug))
                .andExpect(jsonPath("$.active").value(true));

        Team reloaded = teamRepo.findById(team.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getName()).isEqualTo("NewName");
        org.assertj.core.api.Assertions.assertThat(reloaded.getMascot()).isEqualTo("Tigers");
        org.assertj.core.api.Assertions.assertThat(reloaded.getAbbreviation()).isEqualTo(originalAbbreviation);
        org.assertj.core.api.Assertions.assertThat(reloaded.getLogoUrl()).isEqualTo(originalLogo);
        org.assertj.core.api.Assertions.assertThat(reloaded.getActive()).isTrue();
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = """
                {"name": "Anybody"}
                """;

        mockMvc.perform(put("/api/teams/999999")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/teams/{id} ────────────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        // Create a team with no game dependencies
        Team team = mkTeam("DeleteMe");

        mockMvc.perform(delete("/api/teams/{id}", team.getId()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/teams/999999").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
