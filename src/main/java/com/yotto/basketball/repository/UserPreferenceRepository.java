package com.yotto.basketball.repository;

import com.yotto.basketball.entity.UserPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndPrefKey(Long userId, String prefKey);

    List<UserPreference> findByUserIdOrderByPrefKey(Long userId);

    long countByUserId(Long userId);

    void deleteByUserIdAndPrefKey(Long userId, String prefKey);
}
