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
import com.yotto.basketball.util.EasternDates;
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
import java.util.Map;

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
    @Autowired com.yotto.basketball.repository.UserRepository userRepository;
    @Autowired FavoriteTeamService favoriteTeamService;
    @Autowired com.yotto.basketball.repository.SeasonStatisticsRepository seasonStatisticsRepo;

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
    void inSeason_composesResultsSlate_inOrder() {
        mkFinal(a, b, 71, 70, JAN_16.minusDays(1));
        mkScheduled(c, d, JAN_16);
        seasonPhaseService.setOverride(null, JAN_16);

        HomePageService.HomePage page = service.build();

        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.IN_SEASON);
        assertThat(page.panels()).extracting(HomePageService.HomePanel::fragment)
                .containsExactly("your-teams", "results", "slate"); // your-teams = anon teaser
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
    void quietPhase_composesNothingWithoutNews() {
        // mid-summer: OFFSEASON (no news seeded → no panels), brand tagline
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 7, 15));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.OFFSEASON);
        assertThat(page.panels()).isEmpty();
    }

    // ── Phase 3: your-teams strip ──

    private Long mkUser(String name) {
        com.yotto.basketball.entity.User u = new com.yotto.basketball.entity.User();
        u.setUsername(name);
        u.setEmail(name + "@example.com");
        u.setPasswordHash("{noop}x");
        u.setRole(com.yotto.basketball.entity.Role.USER);
        u.setEnabled(true);
        return userRepository.save(u).getId();
    }

    @Test
    void yourTeams_inSeason_showsRankRecordStreakAndNextGame() {
        Long userId = mkUser("strip-user");
        favoriteTeamService.follow(userId, a.getId());
        mkRating(a, 12, 18.0, JAN_16.minusDays(1));
        mkSeasonStats(a, 18, 4, 5);
        mkFinal(a, b, 85, 69, JAN_16.minusDays(1));
        mkScheduled(a, d, JAN_16.plusDays(1));
        seasonPhaseService.setOverride(null, JAN_16);

        var strip = service.build(userId).panels().stream()
                .filter(p -> p.fragment().equals("your-teams")).findFirst().orElseThrow();
        assertThat(strip.model().get("teaser")).isNull();
        @SuppressWarnings("unchecked")
        List<HomePageService.YourTeamRow> rows =
                (List<HomePageService.YourTeamRow>) strip.model().get("rows");
        assertThat(rows).hasSize(1);
        HomePageService.YourTeamRow row = rows.get(0);
        assertThat(row.name()).isEqualTo("Alabama");
        assertThat(row.rank()).isEqualTo(12);
        assertThat(row.record()).isEqualTo("18–4");
        assertThat(row.streak()).isEqualTo("W5");
        assertThat(row.nextGame()).contains("vs Duke");
    }

    @Test
    void yourTeams_losingStreak_rendersAsL() {
        Long userId = mkUser("streak-user");
        favoriteTeamService.follow(userId, a.getId());
        mkSeasonStats(a, 10, 12, -3);
        mkFinal(a, b, 60, 70, JAN_16.minusDays(1));
        seasonPhaseService.setOverride(null, JAN_16);

        var strip = service.build(userId).panels().stream()
                .filter(p -> p.fragment().equals("your-teams")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<HomePageService.YourTeamRow> rows =
                (List<HomePageService.YourTeamRow>) strip.model().get("rows");
        assertThat(rows.get(0).record()).isEqualTo("10–12");
        assertThat(rows.get(0).streak()).isEqualTo("L3");
        assertThat(rows.get(0).nextGame()).isNull(); // no upcoming game → nothing rendered
    }

    @Test
    void yourTeams_anonymousInSeason_getsTeaser_notInOffseason() {
        mkFinal(a, b, 71, 70, JAN_16.minusDays(1));
        seasonPhaseService.setOverride(null, JAN_16);
        var strip = service.build(null).panels().stream()
                .filter(p -> p.fragment().equals("your-teams")).findFirst().orElseThrow();
        assertThat(strip.model().get("teaser")).isEqualTo("anonymous");

        seasonPhaseService.setOverride(null, LocalDate.of(2026, 7, 15));
        assertThat(service.build(null).panels()).extracting(HomePageService.HomePanel::fragment)
                .doesNotContain("your-teams");
    }

    @Test
    void yourTeams_signedInWithoutFavorites_getsFollowTeaser() {
        Long userId = mkUser("empty-user");
        mkFinal(a, b, 71, 70, JAN_16.minusDays(1));
        seasonPhaseService.setOverride(null, JAN_16);
        var strip = service.build(userId).panels().stream()
                .filter(p -> p.fragment().equals("your-teams")).findFirst().orElseThrow();
        assertThat(strip.model().get("teaser")).isEqualTo("no-favorites");
    }

    // ── Phase 4: postseason + epilogue ──

    @Autowired SeasonWrapService seasonWrapService;

    /** NCAA schedule: R64 FINAL Mar 19, R32 SCHEDULED Mar 21, championship placeholder later. */
    private void seedTournament() {
        mkFinal(a, b, 80, 70, LocalDate.of(2026, 3, 8)); // regular-season tail
        mkTourney(a, b, 85, 60, Game.GameStatus.FINAL, LocalDate.of(2026, 3, 19),
                Game.TournamentType.NCAA_TOURNAMENT, "1st Round", "NCAA Tournament", 3, 14);
        mkTourney(c, d, 71, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 3, 19),
                Game.TournamentType.NCAA_TOURNAMENT, "1st Round", "NCAA Tournament", 6, 11);
        mkTourney(a, c, null, null, Game.GameStatus.SCHEDULED, LocalDate.of(2026, 3, 21),
                Game.TournamentType.NCAA_TOURNAMENT, "2nd Round", "NCAA Tournament", 3, 6);
        mkTourney(b, d, 90, 80, Game.GameStatus.FINAL, LocalDate.of(2026, 3, 19),
                Game.TournamentType.NIT, "1st Round", "NIT", null, null);
    }

    @Test
    void postseason_composesTourneyResultsAndSlate_withSeedsAndCollapsedNit() {
        seedTournament();
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 3, 20));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.POSTSEASON);
        var fragments = page.panels().stream().map(HomePageService.HomePanel::fragment).toList();
        assertThat(fragments).contains("tourney-results", "tourney-slate");
        assertThat(fragments).doesNotContain("conf-champ-day", "results", "slate");

        var results = page.panels().stream()
                .filter(p -> p.fragment().equals("tourney-results")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<HomePageService.TourneyRow> rows =
                (List<HomePageService.TourneyRow>) results.model().get("rows");
        assertThat(rows).hasSize(2); // NIT game excluded from the main rows
        assertThat(rows.get(0).homeSeed()).isEqualTo(3);
        assertThat(rows.get(0).round()).isEqualTo("1st Round");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> other = (List<Map<String, Object>>) results.model().get("otherRows");
        assertThat(other).hasSize(1);
        assertThat(other.get(0).get("label").toString()).startsWith("NIT:");

        var slate = page.panels().stream()
                .filter(p -> p.fragment().equals("tourney-slate")).findFirst().orElseThrow();
        assertThat(slate.model().get("date")).isEqualTo(LocalDate.of(2026, 3, 21));
        // next playable round drives the tagline
        assertThat(page.heroTagline()).isEqualTo("2nd Round starts Saturday.");
    }

    @Test
    void selectionSunday_addsConfChampPanel_withAutoBidBadgeAndSeed() {
        seedTournament(); // gives Alabama seed 3 via its NCAA games
        mkTourney(a, b, 77, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 3, 15),
                Game.TournamentType.CONFERENCE_TOURNAMENT, "Final", "SEC Tournament", null, null);
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 3, 15));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().selectionSunday()).isTrue();
        var panel = page.panels().stream()
                .filter(p -> p.fragment().equals("conf-champ-day")).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<HomePageService.ConfChampRow> rows =
                (List<HomePageService.ConfChampRow>) panel.model().get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).winnerName()).isEqualTo("Alabama");
        assertThat(rows.get(0).winnerSeed()).isEqualTo(3);
        assertThat(rows.get(0).tournamentName()).isEqualTo("SEC Tournament");
    }

    @Test
    void epilogue_composesSeasonWrapAndChampionshipResult() {
        seedTournament();
        Game title = mkTourney(a, d, 78, 70, Game.GameStatus.FINAL, LocalDate.of(2026, 4, 6),
                Game.TournamentType.NCAA_TOURNAMENT, "National Championship", "NCAA Tournament", 3, 11);
        PredictionEvaluation pe = new PredictionEvaluation();
        pe.setGame(title);
        pe.setSeason(season);
        pe.setModelType("BRADLEY_TERRY");
        pe.setGameDate(LocalDate.of(2026, 4, 6));
        pe.setPredictedHomeWinProb(0.12); // home (Alabama) won at 12% → most improbable win
        pe.setActualMargin(8);
        pe.setActualTotal(148);
        pe.setHomeWon(true);
        pe.setEvaluatedAt(java.time.LocalDateTime.of(2026, 4, 7, 6, 0));
        evaluationRepo.save(pe);

        seasonWrapService.clearCache();
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 4, 10));

        HomePageService.HomePage page = service.build();
        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.EPILOGUE);
        var fragments = page.panels().stream().map(HomePageService.HomePanel::fragment).toList();
        assertThat(fragments).containsSubsequence("season-wrap", "championship-result");

        var wrapPanel = page.panels().stream()
                .filter(p -> p.fragment().equals("season-wrap")).findFirst().orElseThrow();
        SeasonWrapService.SeasonWrap wrap = (SeasonWrapService.SeasonWrap) wrapPanel.model().get("wrap");
        assertThat(wrap.stats()).anySatisfy(s -> {
            assertThat(s.label()).isEqualTo("National Champions");
            assertThat(s.headline()).isEqualTo("Alabama");
        });
        assertThat(wrap.stats()).anySatisfy(s -> {
            assertThat(s.label()).isEqualTo("Most Improbable Win");
            assertThat(s.detail()).contains("12%");
        });
    }

    private Game mkTourney(Team home, Team away, Integer hs, Integer as, Game.GameStatus status,
                           LocalDate easternDate, Game.TournamentType type, String round,
                           String name, Integer homeSeed, Integer awaySeed) {
        Game g = new Game();
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setHomeScore(hs);
        g.setAwayScore(as);
        g.setStatus(status);
        g.setSeason(season);
        g.setGameDate(easternAfternoonUtc(easternDate));
        g.setTournamentType(type);
        g.setTournamentRound(round);
        g.setTournamentName(name);
        g.setHomeSeed(homeSeed);
        g.setAwaySeed(awaySeed);
        return gameRepo.save(g);
    }

    // ── Phase 2: quiet-phase compositions ──

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
                .containsExactly("preseason-split", "opening-night", "your-teams");

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
    void offseason_history_weekdayFeaturesAModelHitTheBookMissed() {
        LocalDate wednesday = LocalDate.of(2026, 7, 15);
        Game hit  = mkFinal(a, b, 78, 70, LocalDate.of(2026, 1, 10));   // Alabama by 8
        Game dull = mkFinal(c, d, 70, 60, LocalDate.of(2026, 1, 11));   // model off by 4, book close: not a hit
        mkEval(hit,  "MASSEY", 7.5);    // model within 0.5
        mkEval(hit,  "BOOK",  -3.0);    // book had Auburn by 3: off by 11
        mkEval(dull, "MASSEY", 6.0);
        mkEval(dull, "BOOK",   9.5);

        seasonPhaseService.setOverride(null, wednesday);
        HomePageService.HomePage page = service.build();

        assertThat(page.phase().phase()).isEqualTo(SeasonPhase.Phase.OFFSEASON);
        var history = page.panels().stream()
                .filter(p -> p.fragment().equals("history")).findFirst().orElseThrow();
        HomePageService.HistoryView view = (HomePageService.HistoryView) history.model().get("view");
        assertThat(view.gameId()).isEqualTo(hit.getId());
        assertThat(view.kind()).isEqualTo("Hit");
        assertThat(view.framing())
                .isEqualTo("Spot on: the model had Alabama by 7.5; the book had Auburn by 3.0. Alabama won by 8.");
    }

    @Test
    void offseason_history_sundayFeaturesAMissSharedWithTheBook() {
        LocalDate sunday = LocalDate.of(2026, 7, 19);
        Game shock = mkFinal(c, d, 60, 100, LocalDate.of(2026, 1, 10));  // Duke by 40 on the road
        Game hit   = mkFinal(a, b, 78, 70, LocalDate.of(2026, 1, 11));
        mkEval(shock, "MASSEY", 12.0);  // model had Colgate by 12: off by 52
        mkEval(shock, "BOOK",   10.0);  // book had Colgate by 10: off by 50
        mkEval(hit,   "MASSEY",  7.5);  // model within 0.5: not a shared miss, however wrong the book
        mkEval(hit,   "BOOK",   -3.0);

        seasonPhaseService.setOverride(null, sunday);
        var history = service.build().panels().stream()
                .filter(p -> p.fragment().equals("history")).findFirst().orElseThrow();
        HomePageService.HistoryView view = (HomePageService.HistoryView) history.model().get("view");
        assertThat(view.gameId()).isEqualTo(shock.getId());
        assertThat(view.kind()).isEqualTo("Miss");
        assertThat(view.framing())
                .isEqualTo("Nobody saw it coming: the model had Colgate by 12.0, the book had Colgate by 10.0. Duke won by 40.");
    }

    @Test
    void offseason_history_isStableWithinADay_andNeedsBookLines() {
        Game noBook = mkFinal(a, b, 78, 70, LocalDate.of(2026, 1, 10));
        mkEval(noBook, "MASSEY", 8.0);   // perfect call, but nothing to compare against
        seasonPhaseService.setOverride(null, LocalDate.of(2026, 7, 15));
        assertThat(service.build().panels()).noneMatch(p -> p.fragment().equals("history"));

        mkEval(noBook, "BOOK", -2.0);   // book off by 10
        HomePageService.HistoryView first = (HomePageService.HistoryView) service.build().panels().stream()
                .filter(p -> p.fragment().equals("history")).findFirst().orElseThrow().model().get("view");
        HomePageService.HistoryView again = (HomePageService.HistoryView) service.build().panels().stream()
                .filter(p -> p.fragment().equals("history")).findFirst().orElseThrow().model().get("view");
        assertThat(again.gameId()).isEqualTo(first.gameId());
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

    /** Season-stats row via the calc-preferred path (calc fields set, scraped fields left null). */
    private void mkSeasonStats(Team team, int wins, int losses, int streak) {
        Conference conf = conferenceRepo.findAll().stream().findFirst().orElseGet(() -> {
            Conference c = new Conference();
            c.setName("Test Conference");
            c.setAbbreviation("TC");
            c.setEspnId("tc-espn");
            return conferenceRepo.save(c);
        });
        com.yotto.basketball.entity.SeasonStatistics ss = new com.yotto.basketball.entity.SeasonStatistics();
        ss.setTeam(team);
        ss.setSeason(season);
        ss.setConference(conf);
        ss.setCalcWins(wins);
        ss.setCalcLosses(losses);
        ss.setCalcStreak(streak);
        seasonStatisticsRepo.save(ss);
    }

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

    /** An evaluation row for a FINAL game: spread_error = actual margin − predicted spread. */
    private PredictionEvaluation mkEval(Game g, String modelType, double predictedSpread) {
        int margin = g.getHomeScore() - g.getAwayScore();
        PredictionEvaluation pe = new PredictionEvaluation();
        pe.setGame(g);
        pe.setSeason(season);
        pe.setModelType(modelType);
        pe.setGameDate(EasternDates.toEasternDate(g.getGameDate()));
        pe.setPredictedSpread(predictedSpread);
        pe.setSpreadError(margin - predictedSpread);
        pe.setActualMargin(margin);
        pe.setActualTotal(g.getHomeScore() + g.getAwayScore());
        pe.setHomeWon(margin > 0);
        pe.setEvaluatedAt(java.time.LocalDateTime.of(2026, 4, 30, 12, 0));
        return evaluationRepo.save(pe);
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
