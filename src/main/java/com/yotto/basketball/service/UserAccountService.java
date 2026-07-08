package com.yotto.basketball.service;

import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserToken;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.security.RateLimitExceededException;
import com.yotto.basketball.security.RateLimitService;
import com.yotto.basketball.security.SessionInvalidationService;
import com.yotto.basketball.security.TokenService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * All account lifecycle flows: registration, verification, password
 * reset/change, email change, self-deletion, and admin user management.
 * Enumeration stance: the service never reveals through its return values
 * whether an email address has an account (usernames are enumerable by design).
 */
@Service
public class UserAccountService {

    public enum RegistrationResult { SUCCESS, USERNAME_TAKEN, EMAIL_TAKEN }

    static final Pattern USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$");
    static final Set<String> RESERVED_USERNAMES = Set.of(
            "admin", "administrator", "root", "system", "api", "support",
            "moderator", "anonymous", "user", "test", "yotto", "deepfij");
    static final int PASSWORD_MIN = 8;
    static final int PASSWORD_MAX = 64;

    private final UserRepository userRepository;
    private final TokenService tokenService;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;
    private final UserAuditService auditService;
    private final SessionInvalidationService sessionInvalidationService;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;
    private final String baseUrl;

    public UserAccountService(UserRepository userRepository,
                              TokenService tokenService,
                              PasswordEncoder passwordEncoder,
                              ApplicationEventPublisher eventPublisher,
                              UserAuditService auditService,
                              SessionInvalidationService sessionInvalidationService,
                              RateLimitService rateLimitService,
                              MeterRegistry meterRegistry,
                              @Value("${app.base-url:http://localhost:8080}") String baseUrl) {
        this.userRepository = userRepository;
        this.tokenService = tokenService;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
        this.auditService = auditService;
        this.sessionInvalidationService = sessionInvalidationService;
        this.rateLimitService = rateLimitService;
        this.meterRegistry = meterRegistry;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    // ── Registration & verification ─────────────────────────────────────────

    /**
     * Registers a new account (disabled until the email is verified).
     * EMAIL_TAKEN must be presented to the caller exactly like SUCCESS — the
     * real owner gets an "you already have an account" email instead.
     */
    @Transactional
    public RegistrationResult register(String username, String email, String password, String clientIp) {
        if (!rateLimitService.tryConsume("register-ip", clientIp,
                RateLimitService.REGISTER_IP_MAX, RateLimitService.REGISTER_IP_WINDOW)) {
            throw new RateLimitExceededException("Too many registrations from this address");
        }
        username = username.trim();
        email = normalizeEmail(email);
        validateUsername(username);
        validatePassword(password, username, email);

        if (!rateLimitService.tryConsume("register-email", email,
                RateLimitService.REGISTER_EMAIL_MAX, RateLimitService.REGISTER_EMAIL_WINDOW)) {
            throw new RateLimitExceededException("Too many registrations for this email");
        }

        if (userRepository.existsByUsernameIgnoreCase(username)) {
            return RegistrationResult.USERNAME_TAKEN;
        }
        if (userRepository.existsByEmailIgnoreCase(email)) {
            eventPublisher.publishEvent(AccountMailEvent.of(MailKind.ALREADY_REGISTERED, email, null));
            auditService.record(AuditEventType.REGISTERED, null, username,
                    "attempt with existing email");
            return RegistrationResult.EMAIL_TAKEN;
        }

        User user = new User();
        user.setUsername(username);
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole(Role.USER);
        user.setEnabled(false);
        // Flush now so a concurrent duplicate surfaces here (handled by the
        // controller), not at an unexpected commit point
        userRepository.saveAndFlush(user);

        sendVerificationMail(user);
        auditService.record(AuditEventType.REGISTERED, user, null);
        meterRegistry.counter("auth.register").increment();
        return RegistrationResult.SUCCESS;
    }

    /** Consumes a verification token; empty if invalid/expired/used. */
    @Transactional
    public Optional<User> verifyEmail(String rawToken) {
        return tokenService.consume(rawToken, TokenType.EMAIL_VERIFICATION).map(token -> {
            User user = token.getUser();
            user.setEnabled(true);
            user.setEmailVerifiedAt(Instant.now());
            userRepository.save(user);
            auditService.record(AuditEventType.EMAIL_VERIFIED, user, null);
            return user;
        });
    }

    /** Always behaves identically whether or not the email has an account. */
    @Transactional
    public void resendVerification(String email, String clientIp) {
        email = normalizeEmail(email);
        checkRecoveryRateLimits(email, clientIp);
        userRepository.findByEmailIgnoreCase(email)
                .filter(user -> !user.isEnabled())
                .ifPresent(this::sendVerificationMail);
    }

    // ── Password reset ───────────────────────────────────────────────────────

    /** Always behaves identically whether or not the email has an account. */
    @Transactional
    public void forgotPassword(String email, String clientIp) {
        email = normalizeEmail(email);
        checkRecoveryRateLimits(email, clientIp);
        userRepository.findByEmailIgnoreCase(email).ifPresent(user -> {
            String token = tokenService.issue(user, TokenType.PASSWORD_RESET, null);
            eventPublisher.publishEvent(new AccountMailEvent(MailKind.PASSWORD_RESET,
                    user.getEmail(), user.getUsername(),
                    baseUrl + "/reset-password?token=" + token));
            auditService.record(AuditEventType.PASSWORD_RESET_REQUESTED, user, null);
        });
    }

    /**
     * Completes a reset. Validates the new password BEFORE consuming the token
     * so a policy error doesn't burn the single-use link. Returns false when
     * the token is invalid/expired/used.
     */
    @Transactional
    public boolean resetPassword(String rawToken, String newPassword) {
        UserToken peeked = tokenService.peek(rawToken, TokenType.PASSWORD_RESET).orElse(null);
        if (peeked == null) return false;
        User user = peeked.getUser();
        validatePassword(newPassword, user.getUsername(), user.getEmail());

        if (tokenService.consume(rawToken, TokenType.PASSWORD_RESET).isEmpty()) {
            return false; // lost a race with a concurrent consume
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordMustChange(false);
        user.setFailedLoginAttempts(0);
        user.setLockoutExpiresAt(null); // proves email control; admin `locked` stays
        if (!user.isEnabled() && !user.isLocked()) {
            // Reset proves inbox ownership — exactly what verification proves
            user.setEnabled(true);
            user.setEmailVerifiedAt(Instant.now());
        }
        userRepository.save(user);

        sessionInvalidationService.invalidateAll(user.getUsername());
        eventPublisher.publishEvent(AccountMailEvent.of(MailKind.PASSWORD_CHANGED,
                user.getEmail(), user.getUsername()));
        auditService.record(AuditEventType.PASSWORD_RESET_COMPLETED, user, null);
        return true;
    }

    // ── Logged-in account management ─────────────────────────────────────────

    /** Requires the current password; keeps the caller's session alive. */
    @Transactional
    public void changePassword(Long userId, String currentPassword, String newPassword,
                               String currentSessionId) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        validatePassword(newPassword, user.getUsername(), user.getEmail());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordMustChange(false);
        userRepository.save(user);

        sessionInvalidationService.invalidateAllExcept(user.getUsername(), currentSessionId);
        if (user.getEmail() != null) {
            eventPublisher.publishEvent(AccountMailEvent.of(MailKind.PASSWORD_CHANGED,
                    user.getEmail(), user.getUsername()));
        }
        auditService.record(AuditEventType.PASSWORD_CHANGED, user, null);
    }

    /**
     * Starts an email change: confirmation link to the NEW address, heads-up
     * notice to the old one. A new address that is already taken is treated as
     * success (the holder is notified) — no enumeration.
     */
    @Transactional
    public void requestEmailChange(Long userId, String newEmail, String currentPassword) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Current password is incorrect");
        }
        newEmail = normalizeEmail(newEmail);
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("That is already your email address");
        }
        if (!rateLimitService.tryConsume("email-change", String.valueOf(userId),
                RateLimitService.EMAIL_CHANGE_MAX, RateLimitService.EMAIL_CHANGE_WINDOW)) {
            throw new RateLimitExceededException("Too many email change requests");
        }

        if (userRepository.existsByEmailIgnoreCase(newEmail)) {
            eventPublisher.publishEvent(AccountMailEvent.of(MailKind.ALREADY_REGISTERED, newEmail, null));
        } else {
            String token = tokenService.issue(user, TokenType.EMAIL_CHANGE, newEmail);
            eventPublisher.publishEvent(new AccountMailEvent(MailKind.EMAIL_CHANGE_CONFIRM,
                    newEmail, user.getUsername(),
                    baseUrl + "/account/confirm-email?token=" + token));
        }
        if (user.getEmail() != null) {
            eventPublisher.publishEvent(AccountMailEvent.of(MailKind.EMAIL_CHANGE_NOTICE,
                    user.getEmail(), user.getUsername()));
        }
        auditService.record(AuditEventType.EMAIL_CHANGE_REQUESTED, user, "to " + newEmail);
    }

    /**
     * Finishes an email change. The token must belong to the signed-in user
     * (the link lands behind /account/**, so someone else's token must not
     * change someone else's address). Re-checks uniqueness inside the
     * transaction — the address may have been claimed during the confirmation window.
     */
    @Transactional
    public boolean confirmEmailChange(String rawToken, Long currentUserId) {
        UserToken token = tokenService.consume(rawToken, TokenType.EMAIL_CHANGE).orElse(null);
        if (token == null || token.getPayload() == null) return false;
        if (!token.getUser().getId().equals(currentUserId)) return false;
        String newEmail = token.getPayload();
        if (userRepository.existsByEmailIgnoreCase(newEmail)) {
            return false; // claimed in the meantime
        }
        User user = token.getUser();
        String oldEmail = user.getEmail();
        user.setEmail(newEmail);
        user.setEmailVerifiedAt(Instant.now());
        userRepository.save(user);
        auditService.record(AuditEventType.EMAIL_CHANGED, user,
                oldEmail + " -> " + newEmail);
        return true;
    }

    /** Self-service deletion; password re-entry required. */
    @Transactional
    public void deleteSelf(Long userId, String password) {
        User user = getUser(userId);
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalArgumentException("Password is incorrect");
        }
        assertNotLastAdmin(user, "delete");
        auditService.record(AuditEventType.ACCOUNT_DELETED, user, "self-service");
        deleteUser(user);
    }

    // ── Admin user management ────────────────────────────────────────────────

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void setLocked(Long userId, boolean locked, String adminUsername) {
        User user = getUser(userId);
        if (locked) {
            assertNotSelf(user, adminUsername, "lock");
            assertNotLastAdmin(user, "lock");
            user.setLocked(true);
            sessionInvalidationService.invalidateAll(user.getUsername());
            auditService.record(AuditEventType.LOCKED_ADMIN, user, "by " + adminUsername);
        } else {
            user.setLocked(false);
            user.setFailedLoginAttempts(0);
            user.setLockoutExpiresAt(null);
            auditService.record(AuditEventType.UNLOCKED, user, "by " + adminUsername);
        }
        userRepository.save(user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void setRole(Long userId, Role role, String adminUsername) {
        User user = getUser(userId);
        if (user.getRole() == role) return;
        assertNotSelf(user, adminUsername, "change the role of");
        if (role == Role.USER) {
            assertNotLastAdmin(user, "demote");
        }
        Role oldRole = user.getRole();
        user.setRole(role);
        userRepository.save(user);
        // Authorities changed — force re-login
        sessionInvalidationService.invalidateAll(user.getUsername());
        auditService.record(AuditEventType.ROLE_CHANGED, user,
                oldRole + " -> " + role + " by " + adminUsername);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void adminDelete(Long userId, String adminUsername) {
        User user = getUser(userId);
        assertNotSelf(user, adminUsername, "delete");
        assertNotLastAdmin(user, "delete");
        auditService.record(AuditEventType.ACCOUNT_DELETED, user, "by " + adminUsername);
        deleteUser(user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void adminResendVerification(Long userId) {
        User user = getUser(userId);
        if (user.isEnabled()) {
            throw new IllegalArgumentException("Account is already verified");
        }
        if (user.getEmail() == null) {
            throw new IllegalArgumentException("Account has no email address");
        }
        sendVerificationMail(user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public void adminTriggerPasswordReset(Long userId, String adminUsername) {
        User user = getUser(userId);
        if (user.getEmail() == null) {
            throw new IllegalArgumentException("Account has no email address");
        }
        String token = tokenService.issue(user, TokenType.PASSWORD_RESET, null);
        eventPublisher.publishEvent(new AccountMailEvent(MailKind.PASSWORD_RESET,
                user.getEmail(), user.getUsername(),
                baseUrl + "/reset-password?token=" + token));
        auditService.record(AuditEventType.PASSWORD_RESET_REQUESTED, user, "by " + adminUsername);
    }

    // ── Validation & helpers ─────────────────────────────────────────────────

    static void validateUsername(String username) {
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username must be 3-30 characters: letters, digits, dots, dashes, underscores");
        }
        if (RESERVED_USERNAMES.contains(username.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("That username is reserved");
        }
    }

    static void validatePassword(String password, String username, String email) {
        if (password == null || password.length() < PASSWORD_MIN || password.length() > PASSWORD_MAX) {
            throw new IllegalArgumentException(
                    "Password must be between " + PASSWORD_MIN + " and " + PASSWORD_MAX + " characters");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw new IllegalArgumentException("Password must not be your username");
        }
        if (email != null) {
            String localPart = email.split("@", 2)[0];
            if (password.equalsIgnoreCase(email) || password.equalsIgnoreCase(localPart)) {
                throw new IllegalArgumentException("Password must not be your email address");
            }
        }
    }

    static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void sendVerificationMail(User user) {
        String token = tokenService.issue(user, TokenType.EMAIL_VERIFICATION, null);
        eventPublisher.publishEvent(new AccountMailEvent(MailKind.VERIFY,
                user.getEmail(), user.getUsername(),
                baseUrl + "/verify?token=" + token));
    }

    private void checkRecoveryRateLimits(String email, String clientIp) {
        if (!rateLimitService.tryConsume("recovery-ip", clientIp,
                RateLimitService.RECOVERY_IP_MAX, RateLimitService.RECOVERY_IP_WINDOW)
                || !rateLimitService.tryConsume("recovery-email", email,
                RateLimitService.RECOVERY_EMAIL_MAX, RateLimitService.RECOVERY_EMAIL_WINDOW)) {
            throw new RateLimitExceededException("Too many requests");
        }
    }

    private void deleteUser(User user) {
        // Tokens and preferences cascade at the DB level; audit rows keep the
        // username text with user_id nulled
        userRepository.delete(user);
        userRepository.flush();
        sessionInvalidationService.invalidateAll(user.getUsername());
    }

    private void assertNotSelf(User target, String adminUsername, String action) {
        if (target.getUsername().equalsIgnoreCase(adminUsername)) {
            throw new IllegalArgumentException("You cannot " + action + " your own account here");
        }
    }

    private void assertNotLastAdmin(User target, String action) {
        if (target.getRole() == Role.ADMIN
                && userRepository.countByRoleAndEnabledTrueAndLockedFalse(Role.ADMIN) <= 1) {
            throw new IllegalArgumentException("Cannot " + action + " the last admin account");
        }
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
    }
}
