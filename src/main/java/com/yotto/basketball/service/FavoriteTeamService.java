package com.yotto.basketball.service;

import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Followed teams, stored as a CSV of team ids in {@code favorite.team-ids} (follow order
 * preserved). Thin wrapper over {@link UserPreferenceService} — no schema of its own. The cap is
 * enforced here, not just in the UI.
 */
@Service
public class FavoriteTeamService {

    public static final int MAX_FAVORITES = 10;

    private final UserPreferenceService preferenceService;
    private final TeamRepository teamRepository;

    public FavoriteTeamService(UserPreferenceService preferenceService, TeamRepository teamRepository) {
        this.preferenceService = preferenceService;
        this.teamRepository = teamRepository;
    }

    /** The user's followed teams in follow order; ids whose team no longer exists are dropped. */
    @Transactional(readOnly = true)
    public List<Team> getFavorites(Long userId) {
        List<Long> ids = favoriteIds(userId);
        if (ids.isEmpty()) return List.of();
        Map<Long, Team> byId = teamRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(Long userId, Long teamId) {
        return favoriteIds(userId).contains(teamId);
    }

    @Transactional
    public void follow(Long userId, Long teamId) {
        if (!teamRepository.existsById(teamId)) {
            throw new IllegalArgumentException("Unknown team: " + teamId);
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>(favoriteIds(userId));
        if (ids.contains(teamId)) return;
        if (ids.size() >= MAX_FAVORITES) {
            throw new IllegalArgumentException("You can follow up to " + MAX_FAVORITES + " teams");
        }
        ids.add(teamId);
        save(userId, ids);
    }

    @Transactional
    public void unfollow(Long userId, Long teamId) {
        LinkedHashSet<Long> ids = new LinkedHashSet<>(favoriteIds(userId));
        if (!ids.remove(teamId)) return;
        save(userId, ids);
    }

    private List<Long> favoriteIds(Long userId) {
        return preferenceService.get(userId, PreferenceKeys.FAVORITE_TEAM_IDS)
                .map(FavoriteTeamService::parseCsv)
                .orElse(List.of());
    }

    private void save(Long userId, LinkedHashSet<Long> ids) {
        if (ids.isEmpty()) {
            preferenceService.delete(userId, PreferenceKeys.FAVORITE_TEAM_IDS);
            return;
        }
        preferenceService.set(userId, PreferenceKeys.FAVORITE_TEAM_IDS,
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private static List<Long> parseCsv(String csv) {
        List<Long> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                out.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException ignored) {
                // a hand-edited or corrupted value must not break the page
            }
        }
        return out;
    }
}
