package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Quote;
import com.yotto.basketball.repository.QuoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

@AutoConfigureMockMvc
class AdminQuoteControllerTest extends BaseIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired QuoteRepository quoteRepo;

    private Quote mkQuote(String text, String attribution, boolean active) {
        Quote q = new Quote();
        q.setQuoteText(text);
        q.setAttribution(attribution);
        q.setActive(active);
        return quoteRepo.save(q);
    }

    // ── GET /admin/quotes ─────────────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void list_returnsViewWithAllQuotes() throws Exception {
        mkQuote("Quote A", "Author A", true);
        mkQuote("Quote B", "Author B", false);

        mockMvc.perform(get("/admin/quotes"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/quotes"))
                .andExpect(model().attribute("quotes", org.hamcrest.Matchers.hasSize(2)));
    }

    // ── POST /admin/quotes (create) ───────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_validInput_savesActiveQuoteAndRedirects() throws Exception {
        mockMvc.perform(post("/admin/quotes").with(csrf())
                        .param("quoteText", "  We need the funk.  ")
                        .param("attribution", "  Parliament  "))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("success", "Quote added"));

        assertThat(quoteRepo.findAll()).hasSize(1);
        Quote saved = quoteRepo.findAll().get(0);
        // Trimmed on save
        assertThat(saved.getQuoteText()).isEqualTo("We need the funk.");
        assertThat(saved.getAttribution()).isEqualTo("Parliament");
        assertThat(saved.getActive()).isTrue();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankQuoteText_rejectedWithError() throws Exception {
        mockMvc.perform(post("/admin/quotes").with(csrf())
                        .param("quoteText", "   ")
                        .param("attribution", "Someone"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("error", "Quote text and attribution are required"));

        assertThat(quoteRepo.findAll()).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void create_blankAttribution_rejectedWithError() throws Exception {
        mockMvc.perform(post("/admin/quotes").with(csrf())
                        .param("quoteText", "Anger is an energy.")
                        .param("attribution", ""))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Quote text and attribution are required"));

        assertThat(quoteRepo.findAll()).isEmpty();
    }

    // ── GET /admin/quotes/{id}/edit ───────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void editForm_existingQuote_returnsEditView() throws Exception {
        Quote q = mkQuote("Quote A", "Author A", true);

        mockMvc.perform(get("/admin/quotes/{id}/edit", q.getId()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/quotes-edit"))
                .andExpect(model().attribute("quote",
                        org.hamcrest.Matchers.hasProperty("id", org.hamcrest.Matchers.equalTo(q.getId()))));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void editForm_unknownQuote_redirectsWithError() throws Exception {
        mockMvc.perform(get("/admin/quotes/{id}/edit", 999999L))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("error", "Quote not found"));
    }

    // ── POST /admin/quotes/{id} (update) ──────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_existingQuote_writesNewValuesAndRedirects() throws Exception {
        Quote q = mkQuote("Old text", "Old attr", true);

        mockMvc.perform(post("/admin/quotes/{id}", q.getId()).with(csrf())
                        .param("quoteText", "  New text  ")
                        .param("attribution", "  New attr  ")
                        .param("active", "false"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("success", "Quote updated"));

        Quote reloaded = quoteRepo.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getQuoteText()).isEqualTo("New text");
        assertThat(reloaded.getAttribution()).isEqualTo("New attr");
        assertThat(reloaded.getActive()).isFalse();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_unknownQuote_redirectsWithError() throws Exception {
        mockMvc.perform(post("/admin/quotes/{id}", 999999L).with(csrf())
                        .param("quoteText", "Whatever")
                        .param("attribution", "Whomever")
                        .param("active", "true"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("error", "Quote not found"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void update_blankInput_returnsEditViewWithErrorAndDoesNotPersist() throws Exception {
        Quote q = mkQuote("Original", "Author", true);

        mockMvc.perform(post("/admin/quotes/{id}", q.getId()).with(csrf())
                        .param("quoteText", "  ")
                        .param("attribution", "  ")
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/quotes-edit"))
                .andExpect(model().attribute("error", "Quote text and attribution are required"));

        // Persisted row not modified
        Quote reloaded = quoteRepo.findById(q.getId()).orElseThrow();
        assertThat(reloaded.getQuoteText()).isEqualTo("Original");
        assertThat(reloaded.getAttribution()).isEqualTo("Author");
    }

    // ── DELETE /admin/quotes/{id} ─────────────────────────────────────────────

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_existing_removesRow() throws Exception {
        Quote q = mkQuote("To delete", "Someone", true);

        mockMvc.perform(delete("/admin/quotes/{id}", q.getId()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("success", "Quote deleted"));

        assertThat(quoteRepo.findById(q.getId())).isEmpty();
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void delete_unknown_redirectsWithError() throws Exception {
        mockMvc.perform(delete("/admin/quotes/{id}", 999999L).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/quotes"))
                .andExpect(flash().attribute("error", "Quote not found"));
    }
}
