package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserPreferenceServiceTest extends BaseIntegrationTest {

    @Autowired private UserPreferenceService preferenceService;
    @Autowired private UserRepository userRepository;

    private Long userId;

    @BeforeEach
    void fixture() {
        User u = new User();
        u.setUsername("pref-user");
        u.setEmail("pref-user@example.com");
        u.setPasswordHash("{noop}irrelevant");
        u.setRole(Role.USER);
        u.setEnabled(true);
        userId = userRepository.save(u).getId();
    }

    @Test
    void setAndGet_roundTrips() {
        preferenceService.set(userId, "ui.theme", "dark");
        assertThat(preferenceService.get(userId, "ui.theme")).contains("dark");
    }

    @Test
    void set_upsertsExistingKey() {
        preferenceService.set(userId, "ui.theme", "dark");
        preferenceService.set(userId, "ui.theme", "light");

        assertThat(preferenceService.get(userId, "ui.theme")).contains("light");
        assertThat(preferenceService.getAll(userId)).hasSize(1);
    }

    @Test
    void getBoolean_defaultsWhenAbsent() {
        assertThat(preferenceService.getBoolean(userId, PreferenceKeys.DAILY_UPDATE_EMAIL, false)).isFalse();
        preferenceService.set(userId, PreferenceKeys.DAILY_UPDATE_EMAIL, "true");
        assertThat(preferenceService.getBoolean(userId, PreferenceKeys.DAILY_UPDATE_EMAIL, false)).isTrue();
    }

    @Test
    void delete_removesTheKey() {
        preferenceService.set(userId, "ui.theme", "dark");
        preferenceService.delete(userId, "ui.theme");
        assertThat(preferenceService.get(userId, "ui.theme")).isEmpty();
    }

    @Test
    void invalidKey_isRejected() {
        assertThatThrownBy(() -> preferenceService.set(userId, "Bad Key!", "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preferenceService.set(userId, null, "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oversizedOrEmptyValue_isRejected() {
        assertThatThrownBy(() -> preferenceService.set(userId, "ui.theme", ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> preferenceService.set(userId, "ui.theme", "x".repeat(2001)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void perUserLimit_isEnforced() {
        for (int i = 0; i < UserPreferenceService.MAX_PREFS_PER_USER; i++) {
            preferenceService.set(userId, "k" + i, "v");
        }
        assertThatThrownBy(() -> preferenceService.set(userId, "one-too-many", "v"))
                .isInstanceOf(IllegalArgumentException.class);
        // Updating an existing key is still allowed at the cap
        preferenceService.set(userId, "k0", "updated");
        assertThat(preferenceService.get(userId, "k0")).contains("updated");
    }
}
