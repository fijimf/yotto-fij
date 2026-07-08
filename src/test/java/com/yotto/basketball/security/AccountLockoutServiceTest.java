package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserAuditEventRepository;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AccountLockoutServiceTest extends BaseIntegrationTest {

    @Autowired private AccountLockoutService lockoutService;
    @Autowired private UserRepository userRepository;
    @Autowired private UserAuditEventRepository auditRepository;

    private static final String USERNAME = "lock-user";
    private static final String EMAIL = "lock-user@example.com";

    @BeforeEach
    void fixture() {
        User u = new User();
        u.setUsername(USERNAME);
        u.setEmail(EMAIL);
        u.setPasswordHash("{noop}irrelevant");
        u.setRole(Role.USER);
        u.setEnabled(true);
        userRepository.save(u);
    }

    private User user() {
        return userRepository.findByUsernameIgnoreCase(USERNAME).orElseThrow();
    }

    @Test
    void failuresAccumulate_thenLockAtThreshold() {
        for (int i = 0; i < AccountLockoutService.MAX_FAILURES - 1; i++) {
            lockoutService.onLoginFailure(USERNAME);
        }
        assertThat(user().getLockoutExpiresAt()).isNull();

        lockoutService.onLoginFailure(USERNAME);

        User locked = user();
        assertThat(locked.getFailedLoginAttempts()).isEqualTo(AccountLockoutService.MAX_FAILURES);
        assertThat(locked.getLockoutExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void successResetsCounterAndStampsLastLogin() {
        lockoutService.onLoginFailure(USERNAME);
        lockoutService.onLoginFailure(USERNAME);

        lockoutService.onLoginSuccess(USERNAME);

        User u = user();
        assertThat(u.getFailedLoginAttempts()).isZero();
        assertThat(u.getLockoutExpiresAt()).isNull();
        assertThat(u.getLastLoginAt()).isNotNull();
    }

    @Test
    void failureByEmailIdentifier_countsAgainstSameAccount() {
        lockoutService.onLoginFailure(EMAIL);
        assertThat(user().getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void lapsedLockout_startsAFreshCount() {
        User u = user();
        u.setFailedLoginAttempts(AccountLockoutService.MAX_FAILURES);
        u.setLockoutExpiresAt(Instant.now().minusSeconds(60)); // already lapsed
        userRepository.save(u);

        lockoutService.onLoginFailure(USERNAME);

        User after = user();
        assertThat(after.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(after.getLockoutExpiresAt()).isNull();
    }

    @Test
    void unknownUser_isAuditedButNothingCounted() {
        lockoutService.onLoginFailure("nobody-at-all");

        assertThat(auditRepository.findAll())
                .anyMatch(e -> "nobody-at-all".equals(e.getUsername()));
    }

    @Test
    void auditTrail_recordsFailuresAndLock() {
        for (int i = 0; i < AccountLockoutService.MAX_FAILURES; i++) {
            lockoutService.onLoginFailure(USERNAME);
        }
        assertThat(auditRepository.findAll())
                .anyMatch(e -> e.getEventType() == com.yotto.basketball.entity.AuditEventType.LOCKED_AUTO);
    }
}
