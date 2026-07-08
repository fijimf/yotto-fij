package com.yotto.basketball.security;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class UserInitializerTest extends BaseIntegrationTest {

    @Autowired private UserRepository userRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void createsAdmin_withEmailAndForcedPasswordChange() {
        new UserInitializer(userRepository, passwordEncoder, "Admin@Example.com").run(null);

        User admin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isEnabled()).isTrue();
        assertThat(admin.isPasswordMustChange()).isTrue();
        assertThat(admin.getEmail()).isEqualTo("admin@example.com"); // normalized
        assertThat(admin.getEmailVerifiedAt()).isNotNull();
        assertThat(admin.getPasswordHash()).startsWith("{bcrypt}");
    }

    @Test
    void createsAdmin_withoutEmailWhenUnconfigured() {
        new UserInitializer(userRepository, passwordEncoder, "").run(null);

        User admin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertThat(admin.getEmail()).isNull();
    }

    @Test
    void existingAdmin_isLeftAlone_exceptEmailBackfill() {
        new UserInitializer(userRepository, passwordEncoder, "").run(null);
        User admin = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        String originalHash = admin.getPasswordHash();

        // Second run with an email configured: backfills email, keeps password
        new UserInitializer(userRepository, passwordEncoder, "backfill@example.com").run(null);

        User after = userRepository.findByUsernameIgnoreCase("admin").orElseThrow();
        assertThat(after.getEmail()).isEqualTo("backfill@example.com");
        assertThat(after.getPasswordHash()).isEqualTo(originalHash);
        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void emailBackfill_doesNotOverwriteAnExistingEmail() {
        new UserInitializer(userRepository, passwordEncoder, "first@example.com").run(null);
        new UserInitializer(userRepository, passwordEncoder, "second@example.com").run(null);

        assertThat(userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getEmail())
                .isEqualTo("first@example.com");
    }

    @Test
    void emailBackfill_conflictWithAnotherAccount_isSkippedGracefully() {
        User taken = new User();
        taken.setUsername("squatter");
        taken.setEmail("shared@example.com");
        taken.setPasswordHash("{noop}x");
        taken.setEnabled(true);
        userRepository.save(taken);

        new UserInitializer(userRepository, passwordEncoder, "").run(null); // create admin, no email
        new UserInitializer(userRepository, passwordEncoder, "shared@example.com").run(null);

        assertThat(userRepository.findByUsernameIgnoreCase("admin").orElseThrow().getEmail())
                .isNull();
    }
}
