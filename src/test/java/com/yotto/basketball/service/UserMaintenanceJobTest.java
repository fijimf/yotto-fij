package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.TokenType;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserAuditEvent;
import com.yotto.basketball.entity.UserToken;
import com.yotto.basketball.repository.UserAuditEventRepository;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.repository.UserTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserMaintenanceJobTest extends BaseIntegrationTest {

    @Autowired private UserMaintenanceJob job;
    @Autowired private UserRepository userRepository;
    @Autowired private UserTokenRepository tokenRepository;
    @Autowired private UserAuditEventRepository auditRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User saveUser(String username, boolean enabled, Instant createdAt) {
        User u = new User();
        u.setUsername(username);
        u.setEmail(username + "@example.com");
        u.setPasswordHash("{noop}irrelevant");
        u.setRole(Role.USER);
        u.setEnabled(enabled);
        u = userRepository.save(u);
        // @PrePersist stamps now(); backdate directly for the purge scenarios
        jdbcTemplate.update("UPDATE users SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt), u.getId());
        return u;
    }

    @Test
    void purgesLongExpiredTokens_keepsRecentOnes() {
        User user = saveUser("mj-user", true, Instant.now());
        saveToken(user, Instant.now().minus(Duration.ofDays(10)));   // purged
        saveToken(user, Instant.now().minus(Duration.ofHours(1)));   // expired but in retention window
        saveToken(user, Instant.now().plus(Duration.ofHours(1)));    // live

        job.runNightly();

        assertThat(tokenRepository.count()).isEqualTo(2);
    }

    @Test
    void purgesAbandonedUnverifiedAccounts_freesTheirIdentifiers() {
        saveUser("mj-abandoned", false, Instant.now().minus(Duration.ofDays(8)));
        saveUser("mj-fresh-unverified", false, Instant.now().minus(Duration.ofDays(2)));
        saveUser("mj-verified-old", true, Instant.now().minus(Duration.ofDays(400)));

        job.runNightly();

        assertThat(userRepository.existsByUsernameIgnoreCase("mj-abandoned")).isFalse();
        assertThat(userRepository.existsByUsernameIgnoreCase("mj-fresh-unverified")).isTrue();
        assertThat(userRepository.existsByUsernameIgnoreCase("mj-verified-old")).isTrue();
        // The purge is audited
        assertThat(auditRepository.findAll())
                .anyMatch(e -> e.getEventType() == AuditEventType.ACCOUNT_DELETED
                        && "unverified-purge".equals(e.getDetail()));
    }

    @Test
    void purgesOldAuditRows() {
        UserAuditEvent old = new UserAuditEvent();
        old.setEventType(AuditEventType.LOGIN_FAILURE);
        old.setUsername("ancient");
        auditRepository.save(old);
        jdbcTemplate.update("UPDATE user_audit_events SET created_at = ?",
                Timestamp.from(Instant.now().minus(Duration.ofDays(120))));

        job.runNightly();

        assertThat(auditRepository.findAll())
                .noneMatch(e -> "ancient".equals(e.getUsername()));
    }

    @Test
    void clearsLapsedLockouts() {
        User u = saveUser("mj-lapsed", true, Instant.now());
        u.setFailedLoginAttempts(10);
        u.setLockoutExpiresAt(Instant.now().minus(Duration.ofHours(2)));
        userRepository.save(u);

        job.runNightly();

        User after = userRepository.findByUsernameIgnoreCase("mj-lapsed").orElseThrow();
        assertThat(after.getFailedLoginAttempts()).isZero();
        assertThat(after.getLockoutExpiresAt()).isNull();
    }

    @Test
    void purgesStaleRememberMeTokens() {
        jdbcTemplate.update("INSERT INTO persistent_logins (username, series, token, last_used) VALUES (?,?,?,?)",
                "mj-user", "stale-series", "t", Timestamp.from(Instant.now().minus(Duration.ofDays(40))));
        jdbcTemplate.update("INSERT INTO persistent_logins (username, series, token, last_used) VALUES (?,?,?,?)",
                "mj-user", "fresh-series", "t", Timestamp.from(Instant.now()));

        job.runNightly();

        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM persistent_logins", Integer.class);
        assertThat(remaining).isEqualTo(1);
    }

    private int tokenSeq = 0;

    private void saveToken(User user, Instant expiresAt) {
        UserToken t = new UserToken();
        t.setUser(user);
        // Any unique 64-char string works — the job only looks at expires_at
        t.setTokenHash(String.format("%064d", tokenSeq++));
        t.setType(TokenType.EMAIL_VERIFICATION);
        t.setExpiresAt(expiresAt);
        tokenRepository.save(t);
    }
}
