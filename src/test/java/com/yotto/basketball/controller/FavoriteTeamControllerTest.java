package com.yotto.basketball.controller;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.UserRepository;
import com.yotto.basketball.service.FavoriteTeamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class FavoriteTeamControllerTest extends BaseIntegrationTest {

    static final String USERNAME = "follow-user";

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired TeamRepository teamRepository;
    @Autowired FavoriteTeamService favoriteTeamService;

    User user;
    Team team;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setUsername(USERNAME);
        user.setEmail("follow-user@example.com");
        user.setPasswordHash("{noop}x");
        user.setRole(Role.USER);
        user.setEnabled(true);
        userRepository.save(user);

        team = new Team();
        team.setName("Alabama");
        team.setEspnId("ala");
        team.setActive(true);
        teamRepository.save(team);
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void follow_thenUnfollow_swapsButtonFragment() throws Exception {
        mockMvc.perform(post("/teams/{id}/follow", team.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("★ Following")));
        assertThat(favoriteTeamService.isFavorite(user.getId(), team.getId())).isTrue();

        mockMvc.perform(post("/teams/{id}/unfollow", team.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("☆ Follow")));
        assertThat(favoriteTeamService.isFavorite(user.getId(), team.getId())).isFalse();
    }

    @Test
    void follow_requiresAuthentication() throws Exception {
        mockMvc.perform(post("/teams/{id}/follow", team.getId()).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void followError_isRenderedInFragment() throws Exception {
        for (int i = 0; i < FavoriteTeamService.MAX_FAVORITES; i++) {
            Team t = new Team();
            t.setName("Team" + i);
            t.setEspnId("t" + i);
            t.setActive(true);
            teamRepository.save(t);
            favoriteTeamService.follow(user.getId(), t.getId());
        }
        mockMvc.perform(post("/teams/{id}/follow", team.getId()).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("up to 10 teams")));
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void accountRemove_redirectsToAccount() throws Exception {
        favoriteTeamService.follow(user.getId(), team.getId());
        mockMvc.perform(post("/account/favorites/remove").with(csrf())
                        .param("teamId", team.getId().toString()))
                .andExpect(redirectedUrl("/account"));
        assertThat(favoriteTeamService.getFavorites(user.getId())).isEmpty();
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void teamPage_showsFollowButtonForAuthenticatedUser() throws Exception {
        mockMvc.perform(get("/teams/{id}", team.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("☆ Follow")));
    }

    @Test
    @WithUserDetails(value = USERNAME, setupBefore = TestExecutionEvent.TEST_EXECUTION)
    void teamPage_rendersWellFormedFollowUrl() throws Exception {
        // regression: a ternary inside @{...} concatenation once rendered a literal-garbage URL
        mockMvc.perform(get("/teams/{id}", team.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "hx-post=\"/teams/" + team.getId() + "/follow\"")));
    }

    @Test
    void teamPage_hidesFollowButtonForAnonymous() throws Exception {
        mockMvc.perform(get("/teams/{id}", team.getId()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("☆ Follow"))));
    }
}
