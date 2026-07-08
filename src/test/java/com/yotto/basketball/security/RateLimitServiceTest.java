package com.yotto.basketball.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitServiceTest {

    private final RateLimitService service = new RateLimitService();

    @Test
    void allowsUpToMax_thenBlocks() {
        for (int i = 0; i < 3; i++) {
            assertThat(service.tryConsume("b", "key", 3, Duration.ofMinutes(5))).isTrue();
        }
        assertThat(service.tryConsume("b", "key", 3, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void keysAreIndependent() {
        assertThat(service.tryConsume("b", "one", 1, Duration.ofMinutes(5))).isTrue();
        assertThat(service.tryConsume("b", "one", 1, Duration.ofMinutes(5))).isFalse();
        assertThat(service.tryConsume("b", "two", 1, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void bucketsAreIndependent() {
        assertThat(service.tryConsume("login", "k", 1, Duration.ofMinutes(5))).isTrue();
        assertThat(service.tryConsume("register", "k", 1, Duration.ofMinutes(5))).isTrue();
    }

    @Test
    void keysAreCaseInsensitive() {
        assertThat(service.tryConsume("b", "Foo@Example.com", 1, Duration.ofMinutes(5))).isTrue();
        assertThat(service.tryConsume("b", "foo@example.com", 1, Duration.ofMinutes(5))).isFalse();
    }

    @Test
    void windowExpiry_resetsTheCount() throws InterruptedException {
        assertThat(service.tryConsume("b", "k", 1, Duration.ofMillis(50))).isTrue();
        assertThat(service.tryConsume("b", "k", 1, Duration.ofMillis(50))).isFalse();
        Thread.sleep(80);
        assertThat(service.tryConsume("b", "k", 1, Duration.ofMillis(50))).isTrue();
    }

    @Test
    void clear_resetsEverything() {
        assertThat(service.tryConsume("b", "k", 1, Duration.ofMinutes(5))).isTrue();
        service.clear();
        assertThat(service.tryConsume("b", "k", 1, Duration.ofMinutes(5))).isTrue();
    }
}
