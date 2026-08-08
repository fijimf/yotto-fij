package com.yotto.basketball.repository;

import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);

    /** Sendable users (enabled, not admin-locked) holding a preference value — digest recipients. */
    @Query("SELECT p.user FROM UserPreference p WHERE p.prefKey = :key AND p.prefValue = :value " +
           "AND p.user.enabled = true AND p.user.locked = false")
    List<User> findUsersWithPreference(@Param("key") String key, @Param("value") String value);

    List<UserPreference> findByUserIdOrderByPrefKey(Long userId);

    long countByUserId(Long userId);

    void deleteByUserIdAndPrefKey(Long userId, String prefKey);
}
