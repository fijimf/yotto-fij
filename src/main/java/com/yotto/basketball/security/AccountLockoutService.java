package com.yotto.basketball.security;

import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.UserAuditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Database-backed failed-login accounting (survives restarts, unlike the old
 * in-memory LoginAttemptService). MAX_FAILURES bad passwords trigger a
 * self-expiring temporary lockout, distinct from the admin-set indefinite lock.
 */
@Service
public class AccountLockoutService {

    private static final Logger log = LoggerFactory.getLogger(AccountLockoutService.class);

    public static final int MAX_FAILURES = 10;
    public static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final UserRepository userRepository;
    private final UserAuditService auditService;

    public AccountLockoutService(UserRepository userRepository, UserAuditService auditService) {
        this.userRepository = userRepository;
        this.auditService = auditService;
    }

    @Transactional
    public void onLoginFailure(String usernameOrEmail) {
        Optional<User> found = findUser(usernameOrEmail);
        if (found.isEmpty()) {
            // Unknown identifier: audit only — no row to count against
            auditService.record(AuditEventType.LOGIN_FAILURE, null, usernameOrEmail, "unknown user");
            return;
        }
        User user = found.get();

        // A previous lockout that has lapsed starts a fresh count
        if (user.getLockoutExpiresAt() != null && user.getLockoutExpiresAt().isBefore(Instant.now())) {
            user.setLockoutExpiresAt(null);
            user.setFailedLoginAttempts(0);
        }

        user.setFailedLoginAttempts(user.getFailedLoginAttempts() + 1);
        auditService.record(AuditEventType.LOGIN_FAILURE, user,
                "attempt " + user.getFailedLoginAttempts());

        if (user.getFailedLoginAttempts() >= MAX_FAILURES && !user.isTemporarilyLockedOut()) {
            user.setLockoutExpiresAt(Instant.now().plus(LOCKOUT_DURATION));
            auditService.record(AuditEventType.LOCKED_AUTO, user,
                    MAX_FAILURES + " failed attempts");
            log.warn("Account '{}' temporarily locked after {} failed login attempts",
                    user.getUsername(), user.getFailedLoginAttempts());
        }
        userRepository.save(user);
    }

    @Transactional
    public void onLoginSuccess(String usernameOrEmail) {
        findUser(usernameOrEmail).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockoutExpiresAt(null);
            user.setLastLoginAt(Instant.now());
            userRepository.save(user);
            auditService.record(AuditEventType.LOGIN_SUCCESS, user, null);
        });
    }

    private Optional<User> findUser(String usernameOrEmail) {
        if (usernameOrEmail == null || usernameOrEmail.isBlank()) return Optional.empty();
        return userRepository.findByUsernameIgnoreCase(usernameOrEmail)
                .or(() -> userRepository.findByEmailIgnoreCase(usernameOrEmail));
    }
}
