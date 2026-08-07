package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HomePageServiceTest extends BaseIntegrationTest {

    @Autowired HomePageService service;
    @Autowired SeasonPhaseService seasonPhaseService;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired ConferenceRepository conferenceRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;

    static final LocalDate JAN_16 = LocalDate.of(2026, 1, 16);

    Season season;
    Team a, b, c, d;

    @BeforeEach
    void setUp() {
        season = new Season();
        season.setYear(2026);
        season.setStartDate(LocalDate.of(2025, 11, 1));
        season.setEndDate(LocalDate.of(2026, 4, 30));
        seasonRepo.save(season);
        // an early game so the phase resolver sees the season underway
        a = mkTeam("Alabama", "a");
        b = mkTeam("Auburn", "b");
        c = mkTeam("Colgate", "c");
        d = mkTeam("Duke", "d");
        mkFinal(a, b, 80, 70, LocalDate.of(2025, 11, 3));
    }

    @AfterEach
    void clearOverride() {
        seasonPhaseService.clearOverride();
    }

    @Test
    void inSeason_composesResultsSlateExplore_inOrder() {
        mkFinal(a, b, 71, 70, JAN_16.minusDays(1));
        mkScheduled(c, d, JAN_16);
        seasonPhaseService.setOverride(null, JAN_16);

        HomePageService.HomePage page = service.build();

        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);
        assertThat(page.panels()).extracting(HomePageService.HomePanel::fragment)
                .containsExactly("results", "slate", "explore");
        assertThat(page.heroTagline()).contains("1 game");
    }

    @Test
    void resultsPanel_ranksCloseGamesAboveBlowouts_andCapsAtSix() {
        // 8 finals the night before: one 1-point thriller among seven 30-point blowouts
        LocalDate night = JAN_16.minusDays(1);
        mkFinal(a, b, 71, 70, night);           // the thriller
        for (int i = 0; i < 7; i++) {
            mkFinal(c, d, 100, 70, night);
        }
        seasonPhaseService.setOverride(null, JAN_16);

        HomePageService.HomePage page = service.build();
        var results = page.panels().stream()
                .filter(p -> p.fragment().equals("results")).findFirst().orElseThrow();

        @SuppressWarnings("unchecked")
        List<HomePageService.HomeGameRow> rows =
                (List<HomePageService.HomeGameRow>) results.model().get("rows");
        assertThat(rows).hasSize(HomePageService.PANEL_GAME_LIMIT);
        assertThat(results.model().get("total")).isEqualTo(8);
        assertThat(rows.get(0).v().homeScore()).isEqualTo(71); // thriller ranked first
    }

    @Test
    void marqueeFilter_keepsPowerConferenceGames_dropsMidMajors() {
        // Duke is in the ACC; the mid-major thriller scores higher raw interest but is filtered out
        Conference acc = new Conference();
        acc.setName("Atlantic Coast Conference");
        acc.setAbbreviation("ACC");
        acc.setEspnId("acc-espn");
        conferenceRepo.save(acc);
        ConferenceMembership cm = new ConferenceMembership();
        cm.setTeam(d);
        cm.setConference(acc);
        cm.setSeason(season);
        membershipRepo.save(cm);

        LocalDate night = JAN_16.minusDays(1);
        mkFinal(a, b, 71, 70, night);   // mid-major thriller
        mkFinal(d, c, 100, 70, night);  // ACC blowout
        seasonPhaseService.setOverride(null, JAN_16);

        var results = service.build().panels().stream()
                .filter(p -> p.fragment().equals("results")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<HomePageService.HomeGameRow> rows =
                (List<HomePageService.HomeGameRow>) results.model().get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).v().homeTeam().name()).isEqualTo("Duke");
        assertThat(results.model().get("total")).isEqualTo(2); // "All N scores" counts the full night
    }

    @Test
    void marqueeFilter_fallsBackToAllGames_whenNoMarqueeGamesTonight() {
        // marquee set exists (Duke/ACC) but Duke isn't playing → panel falls back to the full slate
        Conference acc = new Conference();
        acc.setName("Atlantic Coast Conference");
        acc.setAbbreviation("ACC");
        acc.setEspnId("acc-espn");
        conferenceRepo.save(acc);
        ConferenceMembership cm = new ConferenceMembership();
        cm.setTeam(d);
        cm.setConference(acc);
        cm.setSeason(season);
        membershipRepo.save(cm);

        mkFinal(a, b, 71, 70, JAN_16.minusDays(1));
        seasonPhaseService.setOverride(null, JAN_16);

        var results = service.build().panels().stream()
                .filter(p -> p.fragment().equals("results")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<HomePageService.HomeGameRow> rows =
                (List<HomePageService.HomeGameRow>) results.model().get("rows");
        assertThat(rows).hasSize(1);
    }

    @Test
    void emptySlateDay_omitsSlatePanel_noEmptyShell() {
        mkFinal(a, b, 71, 70, JAN_16.minusDays(1));
        // no upcoming games at all
        seasonPhaseService.setOverride(null, JAN_16);

        HomePageService.HomePage page = service.build();
        assertThat(page.panels()).extracting(HomePageService.HomePanel::fragment)
                .doesNotContain("slate");
    }

    @Test
    void slateLooksAheadToNextGameDay() {
        mkScheduled(c, d, JAN_16.plusDays(2));  // nothing tonight, games in 2 days
        seasonPhaseService.setOverride(null, JAN_16);

        HomePageService.HomePage page = service.build();
        var slate = page.panels().stream()
                .filter(p -> p.fragment().equals("slate")).findFirst().orElseThrow();
        assertThat(slate.model().get("title")).isEqualTo("Next Up");
        assertThat(slate.model().get("date")).isEqualTo(JAN_16.plusDays(2));
    }

    @Test
    void quietPhase_composesNewsAndExploreOnly() {
        // mid-summer: OFFSEASON (no news seeded → just explore), brand tagline
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 7, 15));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.OFFSEASON);
        assertThat(page.panels()).extracting(HomePageService.HomePanel::fragment)
                .containsExactly("explore");
    }

    @Test
    void preseason_taglineCountsDownToFirstGame() {
        gameRepo.deleteAll();
        seasonRepo.deleteAll();
        Season s27 = new Season();
        s27.setYear(2027);
        s27.setStartDate(LocalDate.of(2026, 11, 1));
        s27.setEndDate(LocalDate.of(2027, 4, 30));
        seasonRepo.save(s27);
        Game opener = new Game();
        opener.setHomeTeam(a);
        opener.setAwayTeam(b);
        opener.setSeason(s27);
        opener.setStatus(Game.GameStatus.SCHEDULED);
        opener.setGameDate(easternEveningUtc(LocalDate.of(2026, 11, 3)));
        gameRepo.save(opener);

        seasonPhaseService.setOverride(null, LocalDate.of(2026, 10, 20));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.PRESEASON);
        assertThat(page.heroTagline()).isEqualTo("Tip-off in 14 days — November 3.");
    }

    // ── fixtures ──

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setAbbreviation(espnId.toUpperCase());
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkFinal(Team home, Team away, int hs, int as, LocalDate easternDate) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(Game.GameStatus.FINAL);
        g.setSeason(season);
        g.setGameDate(easternEveningUtc(easternDate));
        return gameRepo.save(g);
    }

    private Game mkScheduled(Team home, Team away, LocalDate easternDate) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(Game.GameStatus.SCHEDULED);
        g.setSeason(season);
        g.setGameDate(easternEveningUtc(easternDate));
        return gameRepo.save(g);
    }

    private static LocalDateTime easternEveningUtc(LocalDate date) {
        return date.atTime(19, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
