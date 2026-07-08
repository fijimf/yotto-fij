package com.yotto.basketball.security;

import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;

/**
 * Bootstraps the admin account on first startup (random password logged at
 * WARN, forced change on first login) and backfills the admin email from
 * configuration so admin password reset works.
 */
@Component
public class UserInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserInitializer.class);
    private static final String ADMIN_USERNAME = "admin";
    private static final String CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 16;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String adminEmail;

    public UserInitializer(UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           @Value("${app.security.admin-email:}") String adminEmail) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.adminEmail = adminEmail == null ? "" : adminEmail.trim().toLowerCase();
    }

    @Override
    public void run(ApplicationArguments args) {
        User admin = userRepository.findByUsernameIgnoreCase(ADMIN_USERNAME).orElse(null);

        if (admin == null) {
            String password = generatePassword();
            admin = new User();
            admin.setUsername(ADMIN_USERNAME);
            admin.setPasswordHash(passwordEncoder.encode(password));
            admin.setRole(Role.ADMIN);
            admin.setEnabled(true);
            admin.setPasswordMustChange(true);
            applyAdminEmail(admin);
            try {
                userRepository.save(admin);
            } catch (DataIntegrityViolationException e) {
                // Configured admin email already belongs to an account — an
                // email conflict must not stop the app from starting
                log.warn("Admin email {} already in use; creating admin without one", adminEmail);
                admin.setId(null);
                admin.setEmail(null);
                admin.setEmailVerifiedAt(null);
                userRepository.save(admin);
            }
            log.warn("Admin password generated: {}. Change it at /account/password", password);
            return;
        }

        // Migrated admin has no email; backfill from config so password reset works
        if (admin.getEmail() == null && !adminEmail.isBlank()) {
            applyAdminEmail(admin);
            try {
                userRepository.save(admin);
                log.info("Admin email set to {} from configuration", adminEmail);
            } catch (DataIntegrityViolationException e) {
                log.warn("Could not set admin email to {} — already in use by another account", adminEmail);
            }
        }
    }

    private void applyAdminEmail(User admin) {
        if (!adminEmail.isBlank()) {
            admin.setEmail(adminEmail);
            admin.setEmailVerifiedAt(Instant.now());
        }
    }

    private String generatePassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(CHARS.charAt(random.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
