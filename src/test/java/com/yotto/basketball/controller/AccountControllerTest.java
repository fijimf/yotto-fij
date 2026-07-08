package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.AccountMailEvent;
import com.yotto.basketball.service.MailKind;
import com.yotto.basketball.service.MailService;
import com.yotto.basketball.service.PreferenceKeys;
import com.yotto.basketball.service.UserPreferenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * /account flows with a real AppUserDetails principal (@WithUserDetails loads
 * through AppUserDetailsService at TEST_EXECUTION, after @BeforeEach fixtures).
 */
@AutoConfigureMockMvc
class AccountControllerTest extends BaseIntegrationTest {

    static final String USERNAME = "acct-user";
    static final String EMAIL = "acct-user@example.com";
    static final String PASSWORD = "acct-Pass-1!";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private UserPreferenceService preferenceService;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private MailService mailService;

    @BeforeEach
    void createUser() {
        User u = new User();
        u.setUsername(USERNAME);
        u.setEmail(EMAIL);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setRole(Role.USER);
        u.setEnabled(true);
        userRepository.save(u);
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void accountPage_showsProfile() throws Exception {
        mockMvc.perform(get("/account"))
                .andExpect(status().isOk())
                .andExpect(view().name("account/account"))
                .andExpect(model().attributeExists("user", "memberSince", "dailyUpdateEmail"));
    }

    // ── Change password ──────────────────────────────────────────────────────

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changePassword_happyPath() throws Exception {
        mockMvc.perform(post("/account/password").with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "changed-Pass-2!")
                        .param("confirmPassword", "changed-Pass-2!"))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attributeExists("success"));

        User u = userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow();
        assertThat(passwordEncoder.matches("changed-Pass-2!", u.getPasswordHash())).isTrue();

        ArgumentCaptor<AccountMailEvent> captor = ArgumentCaptor.forClass(AccountMailEvent.class);
        verify(mailService, timeout(5000)).send(captor.capture());
        assertThat(captor.getValue().kind()).isEqualTo(MailKind.PASSWORD_CHANGED);
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changePassword_wrongCurrentPassword_rejected() throws Exception {
        mockMvc.perform(post("/account/password").with(csrf())
                        .param("currentPassword", "not-my-password")
                        .param("newPassword", "changed-Pass-2!")
                        .param("confirmPassword", "changed-Pass-2!"))
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(flash().attributeExists("error"));

        User u = userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow();
        assertThat(passwordEncoder.matches(PASSWORD, u.getPasswordHash())).isTrue();
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changePassword_mismatchedConfirmation_rejected() throws Exception {
        mockMvc.perform(post("/account/password").with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "changed-Pass-2!")
                        .param("confirmPassword", "different-3!"))
                .andExpect(redirectedUrl("/account/password"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changePassword_clearsMustChangeFlag() throws Exception {
        User u = userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow();
        u.setPasswordMustChange(true);
        userRepository.save(u);

        mockMvc.perform(post("/account/password").with(csrf())
                        .param("currentPassword", PASSWORD)
                        .param("newPassword", "changed-Pass-2!")
                        .param("confirmPassword", "changed-Pass-2!"))
                .andExpect(redirectedUrl("/account"));

        assertThat(userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow()
                .isPasswordMustChange()).isFalse();
    }

    // ── Change email ─────────────────────────────────────────────────────────

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changeEmail_fullFlow() throws Exception {
        mockMvc.perform(post("/account/email").with(csrf())
                        .param("newEmail", "new-addr@example.com")
                        .param("currentPassword", PASSWORD))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attributeExists("success"));

        // Confirm goes to the NEW address; heads-up notice to the old one
        ArgumentCaptor<AccountMailEvent> captor = ArgumentCaptor.forClass(AccountMailEvent.class);
        verify(mailService, timeout(5000).times(2)).send(captor.capture());
        List<AccountMailEvent> mails = captor.getAllValues();
        AccountMailEvent confirm = mails.stream()
                .filter(m -> m.kind() == MailKind.EMAIL_CHANGE_CONFIRM).findFirst().orElseThrow();
        assertThat(confirm.to()).isEqualTo("new-addr@example.com");
        assertThat(mails).anyMatch(m -> m.kind() == MailKind.EMAIL_CHANGE_NOTICE
                && m.to().equals(EMAIL));

        String token = confirm.link().substring(confirm.link().indexOf("token=") + 6);

        mockMvc.perform(get("/account/confirm-email").param("token", token))
                .andExpect(status().isOk())
                .andExpect(view().name("account/confirm-email"));

        mockMvc.perform(post("/account/confirm-email").with(csrf()).param("token", token))
                .andExpect(redirectedUrl("/account"));

        assertThat(userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow().getEmail())
                .isEqualTo("new-addr@example.com");
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changeEmail_wrongPassword_rejected() throws Exception {
        mockMvc.perform(post("/account/email").with(csrf())
                        .param("newEmail", "new-addr@example.com")
                        .param("currentPassword", "wrong"))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attributeExists("error"));
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void changeEmail_takenAddress_looksLikeSuccess_ownerGetsNotice() throws Exception {
        User other = new User();
        other.setUsername("acct-other");
        other.setEmail("taken@example.com");
        other.setPasswordHash(passwordEncoder.encode(PASSWORD));
        other.setEnabled(true);
        userRepository.save(other);

        mockMvc.perform(post("/account/email").with(csrf())
                        .param("newEmail", "taken@example.com")
                        .param("currentPassword", PASSWORD))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attributeExists("success"));

        ArgumentCaptor<AccountMailEvent> captor = ArgumentCaptor.forClass(AccountMailEvent.class);
        verify(mailService, timeout(5000).atLeastOnce()).send(captor.capture());
        assertThat(captor.getAllValues())
                .anyMatch(m -> m.kind() == MailKind.ALREADY_REGISTERED
                        && m.to().equals("taken@example.com"));
        // Email unchanged
        assertThat(userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow().getEmail())
                .isEqualTo(EMAIL);
    }

    // ── Preferences ──────────────────────────────────────────────────────────

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void preferences_toggleDailyUpdateEmail() throws Exception {
        mockMvc.perform(post("/account/preferences").with(csrf())
                        .param("dailyUpdateEmail", "true"))
                .andExpect(redirectedUrl("/account"));

        Long userId = userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow().getId();
        assertThat(preferenceService.getBoolean(userId, PreferenceKeys.DAILY_UPDATE_EMAIL, false)).isTrue();

        // Unchecked checkbox → param absent → defaults false
        mockMvc.perform(post("/account/preferences").with(csrf()))
                .andExpect(redirectedUrl("/account"));
        assertThat(preferenceService.getBoolean(userId, PreferenceKeys.DAILY_UPDATE_EMAIL, false)).isFalse();
    }

    // ── Delete account ───────────────────────────────────────────────────────

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void deleteAccount_wrongPassword_rejected() throws Exception {
        mockMvc.perform(post("/account/delete").with(csrf())
                        .param("password", "wrong"))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attributeExists("error"));
        assertThat(userRepository.existsByUsernameIgnoreCase(USERNAME)).isTrue();
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void deleteAccount_happyPath_removesUserAndPreferences() throws Exception {
        Long userId = userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow().getId();
        preferenceService.set(userId, PreferenceKeys.DAILY_UPDATE_EMAIL, "true");

        mockMvc.perform(post("/account/delete").with(csrf())
                        .param("password", PASSWORD))
                .andExpect(redirectedUrl("/"));

        assertThat(userRepository.existsByUsernameIgnoreCase(USERNAME)).isFalse();
        assertThat(preferenceService.getAll(userId)).isEmpty();
    }
}
