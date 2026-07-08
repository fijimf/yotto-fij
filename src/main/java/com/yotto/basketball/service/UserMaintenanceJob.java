package com.yotto.basketball.service;

import com.yotto.basketball.entity.AuditEventType;
import com.yotto.basketball.repository.UserAuditEventRepository;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.repository.UserTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

/**
 * Nightly account hygiene: expired tokens, abandoned (never-verified)
 * registrations — freeing their username/email for honest re-use — old audit
 * rows, lapsed lockout counters, and stale remember-me tokens (Spring's
 * JdbcTokenRepositoryImpl never purges its own table).
 */
@Component
public class UserMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(UserMaintenanceJob.class);

    static final Duration TOKEN_RETENTION = Duration.ofDays(7);
    static final Duration UNVERIFIED_RETENTION = Duration.ofDays(7);
    static final Duration AUDIT_RETENTION = Duration.ofDays(90);
    static final Duration REMEMBER_ME_RETENTION = Duration.ofDays(30);

    private final UserRepository userRepository;
    private final UserTokenRepository tokenRepository;
    private final UserAuditEventRepository auditRepository;
    private final UserAuditService auditService;
    private final JdbcTemplate jdbcTemplate;

    public UserMaintenanceJob(UserRepository userRepository,
                              UserTokenRepository tokenRepository,
                              UserAuditEventRepository auditRepository,
                              UserAuditService auditService,
                              JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.auditRepository = auditRepository;
        this.auditService = auditService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.user-maintenance.cron:0 0 3 * * *}")
    @Transactional
    public void runNightly() {
        Instant now = Instant.now();

        long tokens = tokenRepository.deleteByExpiresAtBefore(now.minus(TOKEN_RETENTION));

        var abandoned = userRepository
                .findByEnabledFalseAndEmailVerifiedAtIsNullAndCreatedAtBefore(now.minus(UNVERIFIED_RETENTION));
        abandoned.forEach(user -> {
            auditService.record(AuditEventType.ACCOUNT_DELETED, user, "unverified-purge");
            userRepository.delete(user);
        });

        long auditRows = auditRepository.deleteByCreatedAtBefore(now.minus(AUDIT_RETENTION));

        int lockouts = userRepository.clearExpiredLockouts(now);

        int rememberMe = jdbcTemplate.update(
                "DELETE FROM persistent_logins WHERE last_used < ?",
                Timestamp.from(now.minus(REMEMBER_ME_RETENTION)));

        log.info("User maintenance: {} expired tokens, {} abandoned accounts, "
                        + "{} audit rows, {} lapsed lockouts, {} stale remember-me tokens",
                tokens, abandoned.size(), auditRows, lockouts, rememberMe);
    }
}
