package com.yotto.basketball.repository;

import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsernameIgnoreCase(String username);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByUsernameIgnoreCase(String username);

    boolean existsByEmailIgnoreCase(String email);

    Page<User> findByUsernameContainingIgnoreCaseOrEmailContainingIgnoreCase(
            String username, String email, Pageable pageable);

    /** Admins who could still log in — used for last-admin protection. */
    long countByRoleAndEnabledTrueAndLockedFalse(Role role);

    /** Never-verified accounts older than the cutoff (registration abandonment purge). */
    List<User> findByEnabledFalseAndEmailVerifiedAtIsNullAndCreatedAtBefore(Instant cutoff);

    /** Mailable users for admin broadcasts: verified (enabled), not admin-locked, with an email. */
    List<User> findByEnabledTrueAndLockedFalseAndEmailIsNotNull();

    long countByEnabledTrueAndLockedFalseAndEmailIsNotNull();

    @Modifying
    @Query("UPDATE User u SET u.failedLoginAttempts = 0, u.lockoutExpiresAt = NULL "
            + "WHERE u.lockoutExpiresAt IS NOT NULL AND u.lockoutExpiresAt < :now")
    int clearExpiredLockouts(@Param("now") Instant now);
}
