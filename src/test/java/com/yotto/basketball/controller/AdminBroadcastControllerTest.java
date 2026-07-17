package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.EmailBroadcast;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.security.AppUserDetails;
import com.yotto.basketball.service.BroadcastService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web-layer wiring for the broadcast admin page. Sends use plain form params here
 * rather than real multipart uploads: the production MultipartFilter re-parses the
 * request body, which a MockMvc mock request does not carry, so attachment reading
 * is covered directly in {@link AdminBroadcastAttachmentTest} instead.
 */
@AutoConfigureMockMvc
class AdminBroadcastControllerTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private BroadcastService broadcastService;

    private AppUserDetails admin;

    @BeforeEach
    void setUp() {
        User a = new User();
        a.setUsername("bc-admin");
        a.setEmail("bc-admin@example.com");
        a.setPasswordHash(passwordEncoder.encode("admin-Pass-1!"));
        a.setRole(Role.ADMIN);
        a.setEnabled(true);
        admin = new AppUserDetails(userRepository.save(a));
    }

    @Test
    void compose_rendersViewWithRecipientCount() throws Exception {
        when(broadcastService.recipientCount()).thenReturn(3L);

        mockMvc.perform(get("/admin/broadcast").with(user(admin)))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/broadcast"))
                .andExpect(model().attribute("recipientCount", 3L));
    }

    @Test
    void preview_returnsRenderedHtml() throws Exception {
        when(broadcastService.renderPreviewBody("**hi**")).thenReturn("<p><strong>hi</strong></p>");

        mockMvc.perform(post("/admin/broadcast/preview").with(user(admin)).with(csrf())
                        .param("markdown", "**hi**"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<strong>hi</strong>")));
    }

    @Test
    void send_createsAndDeliversBroadcast() throws Exception {
        when(broadcastService.recipientCount()).thenReturn(3L);
        EmailBroadcast created = new EmailBroadcast();
        created.setId(42L);
        when(broadcastService.create(anyString(), anyString(), anyInt(), any(), anyString()))
                .thenReturn(created);

        mockMvc.perform(post("/admin/broadcast/send").with(user(admin)).with(csrf())
                        .param("subject", "Maintenance Saturday")
                        .param("markdown", "We will be **down** briefly."))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/broadcast"))
                .andExpect(flash().attribute("success", org.hamcrest.Matchers.containsString("3 recipients")));

        verify(broadcastService).create(eq("Maintenance Saturday"),
                eq("We will be **down** briefly."), eq(0), isNull(), eq("bc-admin"));
        verify(broadcastService).deliver(eq(42L), anyList(), eq("bc-admin"));
    }

    @Test
    void send_noRecipients_rejected() throws Exception {
        when(broadcastService.recipientCount()).thenReturn(0L);

        mockMvc.perform(post("/admin/broadcast/send").with(user(admin)).with(csrf())
                        .param("subject", "Hi")
                        .param("markdown", "body"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "There are no verified users to send to."));

        verify(broadcastService, never()).create(anyString(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    void send_blankSubject_rejected() throws Exception {
        mockMvc.perform(post("/admin/broadcast/send").with(user(admin)).with(csrf())
                        .param("subject", "   ")
                        .param("markdown", "body"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("error", "Subject is required."));

        verify(broadcastService, never()).create(anyString(), anyString(), anyInt(), any(), anyString());
    }

    @Test
    void test_sendsToAdminsOwnEmail() throws Exception {
        mockMvc.perform(post("/admin/broadcast/test").with(user(admin)).with(csrf())
                        .param("subject", "Preview me")
                        .param("markdown", "body"))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attribute("success", org.hamcrest.Matchers.containsString("bc-admin@example.com")));

        verify(broadcastService).sendTest(eq("Preview me"), eq("body"), anyList(), eq("bc-admin@example.com"));
    }

    @Test
    void nonAdmin_isForbidden() throws Exception {
        mockMvc.perform(get("/admin/broadcast").with(user("plain").roles("USER")))
                .andExpect(status().isForbidden());
    }
}
