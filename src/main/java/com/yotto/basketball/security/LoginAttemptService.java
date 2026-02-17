package com.yotto.basketball.security;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_SECONDS = 900; // 15 minutes

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    public void loginFailed(String username) {
        attempts.compute(username, (key, record) -> {
            if (record == null || record.isExpired()) {
                return new AttemptRecord(1, Instant.now());
            }
            return new AttemptRecord(record.count + 1, record.firstAttempt);
        });
    }

    public void loginSucceeded(String username) {
        attempts.remove(username);
    }

    public boolean isBlocked(String username) {
        AttemptRecord record = attempts.get(username);
        if (record == null) return false;
        if (record.isExpired()) {
            attempts.remove(username);
            return false;
        }
        return record.count >= MAX_ATTEMPTS;
    }

    private record AttemptRecord(int count, Instant firstAttempt) {
        boolean isExpired() {
            return Instant.now().isAfter(firstAttempt.plusSeconds(LOCKOUT_DURATION_SECONDS));
        }
    }
}
