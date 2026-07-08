package com.yotto.basketball.security;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter for auth-adjacent endpoints. Single-app
 * deployment; state resets on restart, which is acceptable (the per-user
 * lockout counter lives in the database).
 */
@Service
public class RateLimitService {

    // Login attempts per IP
    public static final int LOGIN_IP_MAX = 20;
    public static final Duration LOGIN_IP_WINDOW = Duration.ofMinutes(5);
    // Registrations
    public static final int REGISTER_IP_MAX = 5;
    public static final Duration REGISTER_IP_WINDOW = Duration.ofHours(1);
    public static final int REGISTER_EMAIL_MAX = 3;
    public static final Duration REGISTER_EMAIL_WINDOW = Duration.ofDays(1);
    // Forgot-password / resend-verification
    public static final int RECOVERY_IP_MAX = 10;
    public static final Duration RECOVERY_IP_WINDOW = Duration.ofHours(1);
    public static final int RECOVERY_EMAIL_MAX = 3;
    public static final Duration RECOVERY_EMAIL_WINDOW = Duration.ofHours(1);
    // Email change per user
    public static final int EMAIL_CHANGE_MAX = 3;
    public static final Duration EMAIL_CHANGE_WINDOW = Duration.ofDays(1);

    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Records an attempt against {@code bucket:key} and returns whether it is
     * within the limit. Keys are case-normalized so foo@x.com and FOO@x.com
     * share a window.
     */
    public boolean tryConsume(String bucket, String key, int max, Duration window) {
        cleanupIfOversized();
        String mapKey = bucket + ":" + key.toLowerCase();
        Window w = windows.compute(mapKey, (k, existing) -> {
            Instant now = Instant.now();
            if (existing == null || now.isAfter(existing.start.plus(window))) {
                return new Window(now, 1);
            }
            return new Window(existing.start, existing.count + 1);
        });
        return w.count <= max;
    }

    /** Test hook — integration tests share one app context and one limiter. */
    public void clear() {
        windows.clear();
    }

    /** Drops expired windows once the map grows suspiciously large. */
    private void cleanupIfOversized() {
        if (windows.size() < CLEANUP_THRESHOLD) return;
        Instant cutoff = Instant.now().minus(Duration.ofDays(1));
        windows.entrySet().removeIf(e -> e.getValue().start.isBefore(cutoff));
    }

    private record Window(Instant start, int count) {
    }
}
