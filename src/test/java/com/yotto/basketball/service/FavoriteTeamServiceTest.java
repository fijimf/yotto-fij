package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FavoriteTeamServiceTest extends BaseIntegrationTest {

    @Autowired FavoriteTeamService service;
    @Autowired UserPreferenceService preferenceService;
    @Autowired UserRepository userRepository;
    @Autowired TeamRepository teamRepository;

    User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUsername("fav-user");
        user.setEmail("fav-user@example.com");
        user.setPasswordHash("{noop}x");
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);
    }

    private Team mkTeam(String name) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(name);
        t.setActive(true);
        return teamRepository.save(t);
    }

    @Test
    void followUnfollow_roundTrip_preservesFollowOrder() {
        Team a = mkTeam("Alabama");
        Team b = mkTeam("Butler");
        Team c = mkTeam("Colgate");

        service.follow(user.getId(), b.getId());
        service.follow(user.getId(), a.getId());
        service.follow(user.getId(), c.getId());
        assertThat(service.getFavorites(user.getId())).extracting(Team::getName)
                .containsExactly("Butler", "Alabama", "Colgate");
        assertThat(service.isFavorite(user.getId(), a.getId())).isTrue();

        service.unfollow(user.getId(), a.getId());
        assertThat(service.getFavorites(user.getId())).extracting(Team::getName)
                .containsExactly("Butler", "Colgate");

        service.unfollow(user.getId(), b.getId());
        service.unfollow(user.getId(), c.getId());
        assertThat(service.getFavorites(user.getId())).isEmpty();
        // pref row deleted entirely when the last team is unfollowed
        assertThat(preferenceService.get(user.getId(), PreferenceKeys.FAVORITE_TEAM_IDS)).isEmpty();
    }

    @Test
    void follow_isIdempotent_andRejectsUnknownTeam() {
        Team a = mkTeam("Alabama");
        service.follow(user.getId(), a.getId());
        service.follow(user.getId(), a.getId());
        assertThat(service.getFavorites(user.getId())).hasSize(1);

        assertThatThrownBy(() -> service.follow(user.getId(), 999_999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown team");
    }

    @Test
    void follow_enforcesCap() {
        List<Team> teams = new ArrayList<>();
        for (int i = 0; i < FavoriteTeamService.MAX_FAVORITES + 1; i++) {
            teams.add(mkTeam("Team" + i));
        }
        for (int i = 0; i < FavoriteTeamService.MAX_FAVORITES; i++) {
            service.follow(user.getId(), teams.get(i).getId());
        }
        Team oneTooMany = teams.get(FavoriteTeamService.MAX_FAVORITES);
        assertThatThrownBy(() -> service.follow(user.getId(), oneTooMany.getId()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("up to " + FavoriteTeamService.MAX_FAVORITES);
    }

    @Test
    void corruptCsv_isToleratedAndDeadIdsDropped() {
        Team a = mkTeam("Alabama");
        preferenceService.set(user.getId(), PreferenceKeys.FAVORITE_TEAM_IDS,
                "garbage," + a.getId() + ",999999");
        assertThat(service.getFavorites(user.getId())).extracting(Team::getName)
                .containsExactly("Alabama");
    }
}
