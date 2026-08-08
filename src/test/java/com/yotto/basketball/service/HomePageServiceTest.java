package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.Conference;
import com.yotto.basketball.entity.ConferenceMembership;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.ConferenceMembershipRepository;
import com.yotto.basketball.repository.ConferenceRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
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
    @Autowired TeamPowerRatingSnapshotRepository ratingSnapshotRepo;
    @Autowired PredictionEvaluationRepository evaluationRepo;

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

    // ── Phase 2: quiet-phase compositions ──

    @Test
    void archiveMonthDay_alwaysLandsInTheSeasonSpan_andIsStablePerDay() {
        for (int i = 0; i < 400; i++) {
            LocalDate day = LocalDate.of(2026, 5, 1).plusDays(i);
            java.time.MonthDay md = HomePageService.archiveMonthDay(day);
            LocalDate probe = md.atYear(md.getMonthValue() >= 11 ? 2025 : 2026);
            boolean inSpan = !probe.isBefore(LocalDate.of(2025, 11, 1))
                    && !probe.isAfter(LocalDate.of(2026, 4, 7));
            assertThat(inSpan).as("archive pick %s for %s", md, day).isTrue();
            assertThat(HomePageService.archiveMonthDay(day)).isEqualTo(md); // deterministic
        }
    }

    @Test
    void preseason_showsSplitPanel_rankingsAndLiveCountdown_andOpeningNight() {
        // prior season (2026) ratings exist; upcoming season 2027 has a scraped opener
        mkRating(a, 1, 25.0, LocalDate.of(2026, 4, 6));
        mkRating(d, 2, 23.5, LocalDate.of(2026, 4, 6));
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
        opener.setGameDate(easternAfternoonUtc(LocalDate.of(2026, 11, 3)));
        gameRepo.save(opener);

        seasonPhaseService.setOverride(null, LocalDate.of(2026, 10, 20));
        HomePageService.HomePage page = service.build();

        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.PRESEASON);
        assertThat(page.panels()).extracting(HomePageService.HomePanel::fragment)
                .containsExactly("preseason-split", "opening-night", "explore");

        var split = page.panels().get(0);
        assertThat(split.model().get("title").toString()).startsWith("Never-Too-Early");
        @SuppressWarnings("unchecked")
        List<HomePageService.RankRow> rows = (List<HomePageService.RankRow>) split.model().get("rows");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).name()).isEqualTo("Alabama");
        assertThat(split.model().get("subtitle").toString()).contains("2026");
        // countdown targets the opener's exact tip instant (2 PM ET = 19:00 UTC on Nov 3 2026)
        long expectedTipMs = easternAfternoonUtc(LocalDate.of(2026, 11, 3))
                .toInstant(ZoneOffset.UTC).toEpochMilli();
        assertThat(split.model().get("tipInstantMs")).isEqualTo(expectedTipMs);
        assertThat(split.model().get("tipLabel").toString()).contains("November 3");

        var opening = page.panels().get(1);
        assertThat(opening.model().get("date")).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(opening.model().get("total")).isEqualTo(1);
    }

    @Test
    void preseason_noScheduleYet_splitStillShowsRankings_withoutCountdown() {
        mkRating(a, 1, 25.0, LocalDate.of(2026, 4, 6));
        Season s27 = new Season();
        s27.setYear(2027);
        s27.setStartDate(LocalDate.of(2026, 11, 1));
        s27.setEndDate(LocalDate.of(2027, 4, 30));
        seasonRepo.save(s27);

        seasonPhaseService.setOverride(null, LocalDate.of(2026, 10, 20));
        var split = service.build().panels().stream()
                .filter(p -> p.fragment().equals("preseason-split")).findFirst().orElseThrow();
        assertThat(split.model().get("tipInstantMs")).isNull();
        @SuppressWarnings("unchecked")
        List<HomePageService.RankRow> rows = (List<HomePageService.RankRow>) split.model().get("rows");
        assertThat(rows).hasSize(1);
    }

    @Test
    void offseason_history_picksClosestGameOnArchiveDate() {
        LocalDate wednesday = LocalDate.of(2026, 7, 15);
        java.time.MonthDay md = HomePageService.archiveMonthDay(wednesday);
        LocalDate gameDay = md.atYear(md.getMonthValue() >= 11 ? 2025 : 2026);
        mkFinal(a, b, 71, 70, gameDay);   // the classic
        mkFinal(c, d, 100, 60, gameDay);  // the blowout

        seasonPhaseService.setOverride(null, wednesday);
        HomePageService.HomePage page = service.build();

        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.OFFSEASON);
        var history = page.panels().stream()
                .filter(p -> p.fragment().equals("history")).findFirst().orElseThrow();
        HomePageService.HistoryView view = (HomePageService.HistoryView) history.model().get("view");
        assertThat(view.homeName()).isEqualTo("Alabama");
        assertThat(view.framing()).contains("Decided by 1 point");
    }

    @Test
    void offseason_history_sundayFeaturesBiggestModelMiss() {
        LocalDate sunday = LocalDate.of(2026, 7, 19);
        java.time.MonthDay md = HomePageService.archiveMonthDay(sunday);
        LocalDate gameDay = md.atYear(md.getMonthValue() >= 11 ? 2025 : 2026);
        Game blowout = mkFinal(c, d, 100, 60, gameDay);  // margin 40 vs a predicted −5: miss of 45
        mkFinal(a, b, 71, 70, gameDay);                  // closer game the miss must outrank
        PredictionEvaluation pe = new PredictionEvaluation();
        pe.setGame(blowout);
        pe.setSeason(season);
        pe.setModelType("MASSEY");
        pe.setGameDate(gameDay);
        pe.setPredictedSpread(-5.0);
        pe.setSpreadError(45.0);
        pe.setActualMargin(40);
        pe.setActualTotal(160);
        pe.setHomeWon(true);
        pe.setEvaluatedAt(java.time.LocalDateTime.of(2026, 4, 30, 12, 0));
        evaluationRepo.save(pe);

        seasonPhaseService.setOverride(null, sunday);
        var history = service.build().panels().stream()
                .filter(p -> p.fragment().equals("history")).findFirst().orElseThrow();
        HomePageService.HistoryView view = (HomePageService.HistoryView) history.model().get("view");
        assertThat(view.homeName()).isEqualTo("Colgate");
        assertThat(view.framing()).contains("missed this one by 45.0 points");
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
        opener.setGameDate(easternAfternoonUtc(LocalDate.of(2026, 11, 3)));
        gameRepo.save(opener);

        seasonPhaseService.setOverride(null, LocalDate.of(2026, 10, 20));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.PRESEASON);
        assertThat(page.heroTagline()).isEqualTo("Tip-off in 14 days — November 3.");
    }

    // ── fixtures ──

    private void mkRating(Team team, int rank, double rating, LocalDate snapshotDate) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType("MASSEY");
        s.setSnapshotDate(snapshotDate);
        s.setRating(rating);
        s.setRank(rank);
        s.setGamesPlayed(30);
        s.setCalculatedAt(snapshotDate.atTime(6, 0));
        ratingSnapshotRepo.save(s);
    }

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
        g.setGameDate(easternAfternoonUtc(easternDate));
        return gameRepo.save(g);
    }

    private Game mkScheduled(Team home, Team away, LocalDate easternDate) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(Game.GameStatus.SCHEDULED);
        g.setSeason(season);
        g.setGameDate(easternAfternoonUtc(easternDate));
        return gameRepo.save(g);
    }

    private static LocalDateTime easternAfternoonUtc(LocalDate date) {
        return date.atTime(14, 0).atZone(ZoneId.of("America/New_York"))
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}
