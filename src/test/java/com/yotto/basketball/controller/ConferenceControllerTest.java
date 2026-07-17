package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceNameHistory;
import com.yotto.basketball.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@AutoConfigureMockMvc
@WithMockUser(roles = "ADMIN")
class ConferenceControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceNameHistoryRepository nameHistoryRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;

    private Conference mkConference(String name, String espnId) {
        Conference c = new Conference();
        c.setName(name);
        c.setEspnId(espnId);
        c.setAbbreviation(name);
        c.setDivision("Division I");
        c.setLogoUrl("https://example.com/" + espnId + ".png");
        return conferenceRepo.save(c);
    }

    // ── GET /api/conferences ──────────────────────────────────────────────────

    @Test
    void getAll_emptyReturnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/conferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getAll_returnsAllConferences() throws Exception {
        mkConference("SEC", "sec1");
        mkConference("ACC", "acc1");

        mockMvc.perform(get("/api/conferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ── POST /api/conferences ─────────────────────────────────────────────────

    @Test
    void create_returns201WithFullConferenceRoundTrip() throws Exception {
        String body = """
                {"name": "Big Ten", "espnId": "bt1", "abbreviation": "B1G", "division": "Division I", "logoUrl": "https://example.com/b1g.png"}
                """;

        mockMvc.perform(post("/api/conferences")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.name").value("Big Ten"))
                .andExpect(jsonPath("$.espnId").value("bt1"))
                .andExpect(jsonPath("$.abbreviation").value("B1G"))
                .andExpect(jsonPath("$.division").value("Division I"))
                .andExpect(jsonPath("$.logoUrl").value("https://example.com/b1g.png"));

        Conference saved = conferenceRepo.findByEspnId("bt1").orElseThrow();
        org.assertj.core.api.Assertions.assertThat(saved.getName()).isEqualTo("Big Ten");
        org.assertj.core.api.Assertions.assertThat(saved.getAbbreviation()).isEqualTo("B1G");
        org.assertj.core.api.Assertions.assertThat(saved.getDivision()).isEqualTo("Division I");
        org.assertj.core.api.Assertions.assertThat(saved.getLogoUrl()).isEqualTo("https://example.com/b1g.png");
    }

    @Test
    void create_blankName_returns400() throws Exception {
        String body = """
                {"name": ""}
                """;

        mockMvc.perform(post("/api/conferences")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // ── GET /api/conferences/{id} ─────────────────────────────────────────────

    @Test
    void getById_returnsFullConferenceShape() throws Exception {
        Conference c = mkConference("SEC", "sec1");

        mockMvc.perform(get("/api/conferences/{id}", c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(c.getId()))
                .andExpect(jsonPath("$.name").value("SEC"))
                .andExpect(jsonPath("$.espnId").value("sec1"))
                .andExpect(jsonPath("$.abbreviation").value("SEC"))
                .andExpect(jsonPath("$.division").value("Division I"))
                .andExpect(jsonPath("$.logoUrl").value("https://example.com/sec1.png"))
                .andExpect(jsonPath("$.nameHistory").isArray())
                .andExpect(jsonPath("$.nameHistory.length()").value(0));
    }

    @Test
    void getById_includesNameHistory() throws Exception {
        Conference c = mkConference("United Athletic Conference", "30");
        nameHistoryRepo.save(new ConferenceNameHistory(
                c, "Western Athletic Conference", "WAC", "https://example.com/wac.gif", 2026));

        mockMvc.perform(get("/api/conferences/{id}", c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("United Athletic Conference"))
                .andExpect(jsonPath("$.nameHistory.length()").value(1))
                .andExpect(jsonPath("$.nameHistory[0].name").value("Western Athletic Conference"))
                .andExpect(jsonPath("$.nameHistory[0].abbreviation").value("WAC"))
                .andExpect(jsonPath("$.nameHistory[0].logoUrl").value("https://example.com/wac.gif"))
                .andExpect(jsonPath("$.nameHistory[0].lastSeasonYear").value(2026));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/conferences/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    // ── GET /api/conferences/name/{name} ──────────────────────────────────────

    @Test
    void getByName_returnsConference() throws Exception {
        mkConference("ACC", "acc1");

        mockMvc.perform(get("/api/conferences/name/ACC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("ACC"));
    }

    @Test
    void getByName_notFound_returns404() throws Exception {
        mockMvc.perform(get("/api/conferences/name/NoSuchConf"))
                .andExpect(status().isNotFound());
    }

    // ── PUT /api/conferences/{id} ─────────────────────────────────────────────

    @Test
    void update_persistsEditableFieldsButLeavesLogoUrlAndEspnIdUnchanged() throws Exception {
        // ConferenceService.update only mutates name/abbreviation/division. logoUrl
        // and espnId are deliberately preserved (espn-IDs are scrape-managed and
        // logo URLs are populated by the conference scraper).
        Conference c = mkConference("OldName", "espn1");
        String originalLogo = c.getLogoUrl();

        String body = """
                {"name": "NewName", "abbreviation": "NN", "division": "Division II", "logoUrl": "https://example.com/new.png"}
                """;

        mockMvc.perform(put("/api/conferences/{id}", c.getId())
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("NewName"))
                .andExpect(jsonPath("$.abbreviation").value("NN"))
                .andExpect(jsonPath("$.division").value("Division II"))
                // espnId and logoUrl preserved
                .andExpect(jsonPath("$.espnId").value("espn1"))
                .andExpect(jsonPath("$.logoUrl").value(originalLogo));

        Conference reloaded = conferenceRepo.findById(c.getId()).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(reloaded.getName()).isEqualTo("NewName");
        org.assertj.core.api.Assertions.assertThat(reloaded.getAbbreviation()).isEqualTo("NN");
        org.assertj.core.api.Assertions.assertThat(reloaded.getDivision()).isEqualTo("Division II");
        org.assertj.core.api.Assertions.assertThat(reloaded.getLogoUrl()).isEqualTo(originalLogo);
        org.assertj.core.api.Assertions.assertThat(reloaded.getEspnId()).isEqualTo("espn1");
    }

    @Test
    void update_notFound_returns404() throws Exception {
        String body = """
                {"name": "Whatever"}
                """;

        mockMvc.perform(put("/api/conferences/999999")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /api/conferences/{id} ──────────────────────────────────────────

    @Test
    void delete_returns204() throws Exception {
        Conference c = mkConference("ToDelete", "del1");

        mockMvc.perform(delete("/api/conferences/{id}", c.getId()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void delete_notFound_returns404() throws Exception {
        mockMvc.perform(delete("/api/conferences/999999").with(csrf()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
