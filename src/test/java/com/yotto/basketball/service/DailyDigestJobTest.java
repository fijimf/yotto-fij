package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Role;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.User;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import com.yotto.basketball.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** MailService is @MockBean to capture sends; @DirtiesContext because that swaps a singleton. */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DailyDigestJobTest extends BaseIntegrationTest {

    static final LocalDate JAN_16 = LocalDate.of(2026, 1, 16);

    @Autowired DailyDigestJob job;
    @Autowired SeasonPhaseService seasonPhaseService;
    @Autowired UserRepository userRepository;
    @Autowired UserPreferenceService preferenceService;
    @Autowired FavoriteTeamService favoriteTeamService;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @MockBean MailService mailService;

    Team alabama, auburn;

    @BeforeEach
    void setUp() {
        Season season = new Season();
        season.setYear(2026);
        season.setStartDate(LocalDate.of(2025, 11, 1));
        season.setEndDate(LocalDate.of(2026, 4, 30));
        seasonRepo.save(season);
        alabama = mkTeam("Alabama");
        auburn = mkTeam("Auburn");
        mkGame(season, alabama, auburn, 80, 70, Game.GameStatus.FINAL, LocalDate.of(2025, 11, 3));
        mkGame(season, alabama, auburn, 71, 70, Game.GameStatus.FINAL, JAN_16.minusDays(1));
        mkGame(season, auburn, alabama, null, null, Game.GameStatus.SCHEDULED, JAN_16);
        seasonPhaseService.setOverride(null, JAN_16); // IN_SEASON content for every digest
    }

    @AfterEach
    void clearOverride() {
        seasonPhaseService.clearOverride();
    }

    private User mkUser(String name, boolean optedIn) {
        User u = new User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setPasswordHash("{noop}x");
        u.setRole(Role.USER);
        u.setEnabled(true);
        userRepository.save(u);
        if (optedIn) {
            preferenceService.set(u.getId(), PreferenceKeys.DAILY_UPDATE_EMAIL, "true");
        }
        return u;
    }

    @Test
    void sendsPersonalizedDigests_toOptedInUsersOnly() {
        mkUser("digest-plain", true);
        User fan = mkUser("digest-fan", true);
        mkUser("digest-optout", false);
        favoriteTeamService.follow(fan.getId(), alabama.getId());

        int sent = job.runOnce();

        assertThat(sent).isEqualTo(2);
        ArgumentCaptor<BroadcastEmail> captor = ArgumentCaptor.forClass(BroadcastEmail.class);
        verify(mailService, times(2)).sendBroadcast(captor.capture());
        List<BroadcastEmail> emails = captor.getAllValues();
        assertThat(emails).extracting(BroadcastEmail::to)
                .containsExactlyInAnyOrder("digest-plain@example.com", "digest-fan@example.com");
        assertThat(emails).allSatisfy(e -> {
            assertThat(e.subject()).startsWith("DeepFij Daily");
            assertThat(e.html()).contains("Last Night").contains("/account"); // manage-prefs footer
        });
        // personalization: only the fan's email carries the Your Teams section
        String fanHtml = emails.stream().filter(e -> e.to().startsWith("digest-fan")).findFirst().orElseThrow().html();
        String plainHtml = emails.stream().filter(e -> e.to().startsWith("digest-plain")).findFirst().orElseThrow().html();
        assertThat(fanHtml).contains("Your Teams").contains("Alabama");
        assertThat(plainHtml).doesNotContain("Your Teams");
    }

    @Test
    void secondRunSameDay_noOps() {
        mkUser("digest-once", true);
        assertThat(job.runOnce()).isEqualTo(1);
        assertThat(job.runOnce()).isZero();
        verify(mailService, times(1)).sendBroadcast(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyDigest_isNotSent() {
        // deep off-season with no news and no favorites: nothing beyond archive trivia → no email
        gameRepo.deleteAll();
        mkUser("digest-empty", true);
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 7, 15));

        assertThat(job.runOnce()).isZero();
        verify(mailService, times(0)).sendBroadcast(org.mockito.ArgumentMatchers.any());
    }

    // ── fixtures ──

    private Team mkTeam(String name) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(name);
        t.setAbbreviation(name.substring(0, 3).toUpperCase());
        t.setActive(true);
        return teamRepo.save(t);
    }

    private void mkGame(Season s, Team home, Team away, Integer hs, Integer as,
                        Game.GameStatus status, LocalDate easternDate) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(status);
        g.setSeason(s);
        g.setGameDate(easternDate.atTime(14, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime());
        gameRepo.save(g);
    }
}
