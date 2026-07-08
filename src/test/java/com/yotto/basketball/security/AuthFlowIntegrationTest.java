package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.AccountMailEvent;
import com.yotto.basketball.service.MailKind;
import com.yotto.basketball.service.MailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

/**
 * End-to-end account flows: register → verify → login, forgot → reset → login,
 * plus the enumeration-resistance and single-use-token guarantees. Emails are
 * captured through a mocked MailService; the async post-commit listener means
 * every capture uses a timeout.
 */
@AutoConfigureMockMvc
class AuthFlowIntegrationTest extends BaseIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @MockBean private MailService mailService;

    private static final String PASSWORD = "s0lid-Pass!";

    // ── Registration → verification → login ─────────────────────────────────

    @Test
    void fullRegistrationFlow() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "alice")
                        .param("email", "Alice@Example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(view().name("auth/check-email"));

        User alice = userRepository.findByUsernameIgnoreCase("alice").orElseThrow();
        assertThat(alice.isEnabled()).isFalse();
        assertThat(alice.getEmail()).isEqualTo("alice@example.com"); // normalized

        // Unverified login attempt → actionable message, not a session
        mockMvc.perform(formLogin("/login").user("alice").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error=unverified"));

        String token = capturedToken(MailKind.VERIFY);

        // GET renders a confirm page without consuming (mail-scanner safety)
        mockMvc.perform(get("/verify").param("token", token))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/verify"));
        assertThat(userRepository.findByUsernameIgnoreCase("alice").orElseThrow().isEnabled()).isFalse();

        // POST consumes and enables
        mockMvc.perform(post("/verify").with(csrf()).param("token", token))
                .andExpect(redirectedUrl("/login?verified"));
        assertThat(userRepository.findByUsernameIgnoreCase("alice").orElseThrow().isEnabled()).isTrue();

        mockMvc.perform(formLogin("/login").user("alice").password(PASSWORD))
                .andExpect(redirectedUrl("/"));

        // Token is single-use
        mockMvc.perform(post("/verify").with(csrf()).param("token", token))
                .andExpect(view().name("auth/link-expired"));
    }

    @Test
    void register_takenUsername_showsFieldError() throws Exception {
        saveUser("bob", "bob@example.com", true);

        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "BOB") // case-insensitive collision
                        .param("email", "other@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeHasFieldErrors("form", "username"));
    }

    @Test
    void register_takenEmail_looksExactlyLikeSuccess_andMailsTheOwner() throws Exception {
        saveUser("carol", "carol@example.com", true);

        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "carol2")
                        .param("email", "CAROL@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(view().name("auth/check-email"));

        assertThat(userRepository.existsByUsernameIgnoreCase("carol2")).isFalse();
        AccountMailEvent mail = capturedMail(MailKind.ALREADY_REGISTERED);
        assertThat(mail.to()).isEqualTo("carol@example.com");
    }

    @Test
    void register_reservedUsername_isRejected() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "root")
                        .param("email", "root@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(view().name("auth/register"))
                .andExpect(model().attributeExists("error"));
    }

    @Test
    void register_honeypotFilled_fakesSuccessAndCreatesNothing() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "botuser")
                        .param("email", "bot@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD)
                        .param("website", "http://spam.example"))
                .andExpect(view().name("auth/check-email"));

        assertThat(userRepository.existsByUsernameIgnoreCase("botuser")).isFalse();
        verifyNoInteractions(mailService);
    }

    // ── Forgot / reset password ──────────────────────────────────────────────

    @Test
    void fullPasswordResetFlow() throws Exception {
        saveUser("dave", "dave@example.com", true);

        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "dave@example.com"))
                .andExpect(view().name("auth/check-email"));

        String token = capturedToken(MailKind.PASSWORD_RESET);

        mockMvc.perform(get("/reset-password").param("token", token))
                .andExpect(status().isOk())
                .andExpect(view().name("auth/reset-password"));

        String newPassword = "brand-New-1!";
        mockMvc.perform(post("/reset-password").with(csrf())
                        .param("token", token)
                        .param("password", newPassword)
                        .param("confirmPassword", newPassword))
                .andExpect(redirectedUrl("/login?reset"));

        mockMvc.perform(formLogin("/login").user("dave").password(PASSWORD))
                .andExpect(redirectedUrl("/login?error=bad"));
        mockMvc.perform(formLogin("/login").user("dave").password(newPassword))
                .andExpect(redirectedUrl("/"));

        // Reset link is single-use
        mockMvc.perform(post("/reset-password").with(csrf())
                        .param("token", token)
                        .param("password", "another-One-2!")
                        .param("confirmPassword", "another-One-2!"))
                .andExpect(view().name("auth/link-expired"));
    }

    @Test
    void forgotPassword_unknownEmail_sameResponseNoMail() throws Exception {
        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "who@example.com"))
                .andExpect(view().name("auth/check-email"));

        // Give the async listener a moment to (not) fire
        Thread.sleep(300);
        verifyNoInteractions(mailService);
    }

    @Test
    void resetPassword_unverifiedAccount_becomesVerified() throws Exception {
        // Proving inbox control via reset is exactly what verification proves
        saveUser("eve", "eve@example.com", false);

        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "eve@example.com"))
                .andExpect(status().isOk());
        String token = capturedToken(MailKind.PASSWORD_RESET);

        mockMvc.perform(post("/reset-password").with(csrf())
                        .param("token", token)
                        .param("password", "fresh-Pass-3!")
                        .param("confirmPassword", "fresh-Pass-3!"))
                .andExpect(redirectedUrl("/login?reset"));

        assertThat(userRepository.findByUsernameIgnoreCase("eve").orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void resetPassword_policyViolation_doesNotBurnToken() throws Exception {
        saveUser("frank", "frank@example.com", true);
        mockMvc.perform(post("/forgot-password").with(csrf())
                        .param("email", "frank@example.com"));
        String token = capturedToken(MailKind.PASSWORD_RESET);

        // New password equals the account email → rejected, token must survive
        mockMvc.perform(post("/reset-password").with(csrf())
                        .param("token", token)
                        .param("password", "frank@example.com")
                        .param("confirmPassword", "frank@example.com"))
                .andExpect(view().name("auth/reset-password"))
                .andExpect(model().attributeExists("error"));

        mockMvc.perform(post("/reset-password").with(csrf())
                        .param("token", token)
                        .param("password", "valid-Pass-4!")
                        .param("confirmPassword", "valid-Pass-4!"))
                .andExpect(redirectedUrl("/login?reset"));
    }

    @Test
    void expiredOrGarbageToken_rendersLinkExpired() throws Exception {
        mockMvc.perform(get("/verify").param("token", "garbage"))
                .andExpect(view().name("auth/link-expired"));
        mockMvc.perform(get("/reset-password").param("token", "garbage"))
                .andExpect(view().name("auth/link-expired"));
    }

    @Test
    void resendVerification_sendsFreshLink_andInvalidatesOld() throws Exception {
        mockMvc.perform(post("/register").with(csrf())
                        .param("username", "grace")
                        .param("email", "grace@example.com")
                        .param("password", PASSWORD)
                        .param("confirmPassword", PASSWORD))
                .andExpect(status().isOk());
        String first = capturedToken(MailKind.VERIFY);

        mockMvc.perform(post("/verify/resend").with(csrf())
                        .param("email", "grace@example.com"))
                .andExpect(view().name("auth/check-email"));

        ArgumentCaptor<AccountMailEvent> captor = ArgumentCaptor.forClass(AccountMailEvent.class);
        verify(mailService, timeout(5000).times(2)).send(captor.capture());
        String second = tokenFromLink(captor.getAllValues().get(1).link());

        // Old link dead, new link works
        mockMvc.perform(post("/verify").with(csrf()).param("token", first))
                .andExpect(view().name("auth/link-expired"));
        mockMvc.perform(post("/verify").with(csrf()).param("token", second))
                .andExpect(redirectedUrl("/login?verified"));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void saveUser(String username, String email, boolean enabled) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(PASSWORD));
        u.setRole(Role.USER);
        u.setEnabled(enabled);
        userRepository.save(u);
    }

    private AccountMailEvent capturedMail(MailKind kind) {
        ArgumentCaptor<AccountMailEvent> captor = ArgumentCaptor.forClass(AccountMailEvent.class);
        verify(mailService, timeout(5000)).send(captor.capture());
        AccountMailEvent mail = captor.getValue();
        assertThat(mail.kind()).isEqualTo(kind);
        return mail;
    }

    private String capturedToken(MailKind kind) {
        return tokenFromLink(capturedMail(kind).link());
    }

    private static String tokenFromLink(String link) {
        assertThat(link).contains("token=");
        return link.substring(link.indexOf("token=") + "token=".length());
    }
}
