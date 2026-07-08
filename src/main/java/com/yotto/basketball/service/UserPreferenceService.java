package com.yotto.basketball.service;

import com.yotto.basketball.entity.User;
import com.yotto.basketball.entity.UserPreference;
import com.yotto.basketball.repository.UserPreferenceRepository;
import com.yotto.basketball.repository.UserRepository;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Skinny per-user key/value store. Values are opaque strings — callers own
 * key naming (see {@link PreferenceKeys}) and value parsing.
 */
@Service
public class UserPreferenceService {

    static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9._-]{1,100}$");
    static final int VALUE_MAX = 2000;
    static final int MAX_PREFS_PER_USER = 100;

    private final UserPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;

    public UserPreferenceService(UserPreferenceRepository preferenceRepository,
                                 UserRepository userRepository) {
        this.preferenceRepository = preferenceRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<String> get(Long userId, String key) {
        return preferenceRepository.findByUserIdAndPrefKey(userId, key)
                .map(UserPreference::getPrefValue);
    }

    @Transactional(readOnly = true)
    public boolean getBoolean(Long userId, String key, boolean defaultValue) {
        return get(userId, key).map(Boolean::parseBoolean).orElse(defaultValue);
    }

    @Transactional(readOnly = true)
    public Map<String, String> getAll(Long userId) {
        Map<String, String> result = new LinkedHashMap<>();
        preferenceRepository.findByUserIdOrderByPrefKey(userId)
                .forEach(p -> result.put(p.getPrefKey(), p.getPrefValue()));
        return result;
    }

    @Transactional
    public void set(Long userId, String key, String value) {
        if (key == null || !KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException(
                    "Preference key must be 1-100 chars of [a-z0-9._-]");
        }
        if (value == null || value.isEmpty() || value.length() > VALUE_MAX) {
            throw new IllegalArgumentException(
                    "Preference value must be 1-" + VALUE_MAX + " characters");
        }

        UserPreference pref = preferenceRepository.findByUserIdAndPrefKey(userId, key).orElse(null);
        if (pref == null) {
            if (preferenceRepository.countByUserId(userId) >= MAX_PREFS_PER_USER) {
                throw new IllegalArgumentException(
                        "Preference limit reached (" + MAX_PREFS_PER_USER + ")");
            }
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
            pref = new UserPreference();
            pref.setUser(user);
            pref.setPrefKey(key);
        }
        pref.setPrefValue(value);
        preferenceRepository.save(pref);
    }

    @Transactional
    public void delete(Long userId, String key) {
        preferenceRepository.deleteByUserIdAndPrefKey(userId, key);
    }
}
