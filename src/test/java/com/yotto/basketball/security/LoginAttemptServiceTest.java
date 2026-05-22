package com.yotto.basketball.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure JUnit test — no Spring context. Exercises the in-memory lockout state
 * machine including the expiry path (which would otherwise take 15 minutes
 * of wall-clock time to observe).
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void unknownUser_isNotBlocked() {
        assertThat(service.isBlocked("nobody")).isFalse();
    }

    @Test
    void fewerThanMaxAttempts_doesNotBlock() {
        for (int i = 0; i < 4; i++) {
            service.loginFailed("alice");
        }
        assertThat(service.isBlocked("alice")).isFalse();
    }

    @Test
    void fifthFailure_blocksAccount() {
        for (int i = 0; i < 5; i++) {
            service.loginFailed("alice");
        }
        assertThat(service.isBlocked("alice")).isTrue();
    }

    @Test
    void additionalFailuresAfterLock_remainsBlocked() {
        for (int i = 0; i < 8; i++) {
            service.loginFailed("alice");
        }
        assertThat(service.isBlocked("alice")).isTrue();
    }

    @Test
    void loginSucceeded_resetsCounter() {
        for (int i = 0; i < 5; i++) {
            service.loginFailed("alice");
        }
        assertThat(service.isBlocked("alice")).isTrue();

        service.loginSucceeded("alice");

        assertThat(service.isBlocked("alice")).isFalse();
        // And the user can fail again without immediately tripping the lock
        service.loginFailed("alice");
        assertThat(service.isBlocked("alice")).isFalse();
    }

    @Test
    void blocksArePerUsername() {
        for (int i = 0; i < 5; i++) {
            service.loginFailed("alice");
        }

        assertThat(service.isBlocked("alice")).isTrue();
        assertThat(service.isBlocked("bob")).isFalse();
    }

    @Test
    void loginSucceededForUserWithNoRecord_isNoOp() {
        // Should not throw and should not create a record
        service.loginSucceeded("never-tried-before");
        assertThat(service.isBlocked("never-tried-before")).isFalse();
    }

    @Test
    void expiredLock_isReleasedAndRecordCleared() throws Exception {
        for (int i = 0; i < 5; i++) {
            service.loginFailed("alice");
        }
        assertThat(service.isBlocked("alice")).isTrue();

        // Reach into the internal map and back-date the firstAttempt past the
        // 15-minute lockout window so the isExpired() branch fires.
        backdateAttempt(service, "alice", Instant.now().minusSeconds(901));

        assertThat(service.isBlocked("alice"))
                .as("expired lock should release on next isBlocked check").isFalse();
        // And the internal map entry should have been removed by the cleanup
        // branch inside isBlocked().
        assertThat(internalAttemptsMap(service)).doesNotContainKey("alice");
    }

    @Test
    void failureAfterExpiredWindow_startsFreshCount() throws Exception {
        for (int i = 0; i < 5; i++) {
            service.loginFailed("alice");
        }
        backdateAttempt(service, "alice", Instant.now().minusSeconds(901));

        // Next failure: the compute() lambda must replace the expired record with
        // a count=1 record, not increment to 6.
        service.loginFailed("alice");

        assertThat(service.isBlocked("alice")).isFalse();
    }

    // ── Reflection helpers (the alternative is waiting 15 minutes) ─────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> internalAttemptsMap(LoginAttemptService service) throws Exception {
        Field f = LoginAttemptService.class.getDeclaredField("attempts");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(service);
    }

    /** Replace the AttemptRecord for a user with one whose firstAttempt is in the past. */
    private static void backdateAttempt(LoginAttemptService service, String username, Instant when) throws Exception {
        Map<String, Object> map = internalAttemptsMap(service);
        Object existing = map.get(username);
        if (existing == null) return;

        Class<?> recordCls = Class.forName("com.yotto.basketball.security.LoginAttemptService$AttemptRecord");
        Field countField = recordCls.getDeclaredField("count");
        countField.setAccessible(true);
        int count = (int) countField.get(existing);

        Constructor<?> ctor = recordCls.getDeclaredConstructor(int.class, Instant.class);
        ctor.setAccessible(true);
        Object replacement = ctor.newInstance(count, when);
        map.put(username, replacement);
    }
}
