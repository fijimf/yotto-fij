package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.*;
import com.yotto.basketball.repository.*;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

class PredictionServiceTest extends BaseIntegrationTest {

    @Autowired PredictionService service;
    @Autowired GameRepository gameRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired SeasonPopulationStatRepository popStatRepo;
    @Autowired TeamSeasonStatSnapshotRepository snapshotRepo;
    @Autowired SeasonStatisticsRepository statsRepo;
    @Autowired ConferenceMembershipRepository membershipRepo;
    @Autowired ConferenceRepository conferenceRepo;

    Season season;
    Team homeTeam, awayTeam;

    // Snapshot date must be strictly before game date
    static final LocalDate SNAPSHOT_DATE = LocalDate.of(2025, 1, 14);
    static final LocalDate GAME_DATE     = LocalDate.of(2025, 1, 20);

    @BeforeEach
    void setUp() {

        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        seasonRepo.save(season);

        homeTeam = mkTeam("Alabama", "TA");
        awayTeam = mkTeam("Auburn", "TB");
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkGame(Game.GameStatus status, LocalDate date) {
        Game g = new Game();
        g.setHomeTeam(homeTeam);
        g.setAwayTeam(awayTeam);
        g.setStatus(status);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    private void addRatingSnapshot(Team team, String modelType, double rating, LocalDate date) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(date);
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void addParamSnapshot(String modelType, String paramName, double value, LocalDate date) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setParamName(paramName);
        p.setParamValue(value);
        p.setSnapshotDate(date);
        p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }

    private void addAllRatings(double masseyH, double masseyA, double hca,
                               double mtH, double mtA, double hcaTotal,
                               double btH, double btA, double btAlpha) {
        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE, masseyH, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE, masseyA, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE, "hca", hca, SNAPSHOT_DATE);

        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE_TOTALS, mtH, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE_TOTALS, mtA, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", hcaTotal, SNAPSHOT_DATE);

        addRatingSnapshot(homeTeam, BradleyTerryRatingService.MODEL_TYPE, btH, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, BradleyTerryRatingService.MODEL_TYPE, btA, SNAPSHOT_DATE);
        addParamSnapshot(BradleyTerryRatingService.MODEL_TYPE, "hca", btAlpha, SNAPSHOT_DATE);

        addRatingSnapshot(homeTeam, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, btH, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, btA, SNAPSHOT_DATE);
        addParamSnapshot(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", btAlpha, SNAPSHOT_DATE);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * The rolling-form window feeding the ML feature vector must not cross season
     * boundaries: a team's first game of a season is a cold start even when it has
     * plenty of prior-season history (mirrors the trainer's season-scoped
     * team_game_index).
     */
    @Test
    void recentFinalGamesQuery_isSeasonScoped() {
        Season prior = new Season();
        prior.setYear(2024);
        prior.setStartDate(LocalDate.of(2023, 11, 1));
        prior.setEndDate(LocalDate.of(2024, 4, 30));
        seasonRepo.save(prior);

        // Three prior-season FINAL games and one current-season game, all before GAME_DATE
        mkFinalGame(prior, LocalDate.of(2024, 3, 1), 80, 70);
        mkFinalGame(prior, LocalDate.of(2024, 3, 5), 75, 60);
        mkFinalGame(prior, LocalDate.of(2024, 3, 9), 90, 85);
        Game currentSeasonGame = mkFinalGame(season, LocalDate.of(2025, 1, 10), 66, 61);

        List<Game> recent = gameRepo.findRecentFinalGamesForTeam(
                homeTeam.getId(), season.getId(), GAME_DATE.atTime(20, 0),
                org.springframework.data.domain.PageRequest.of(0, 5));

        assertThat(recent).hasSize(1);
        assertThat(recent.get(0).getId()).isEqualTo(currentSeasonGame.getId());

        // First game of the current season for a team with only prior-season games → cold start
        List<Game> priorOnly = gameRepo.findRecentFinalGamesForTeam(
                homeTeam.getId(), season.getId(), LocalDate.of(2025, 1, 5).atTime(20, 0),
                org.springframework.data.domain.PageRequest.of(0, 5));
        assertThat(priorOnly).isEmpty();
    }

    private Game mkFinalGame(Season s, LocalDate date, int homeScore, int awayScore) {
        Game g = new Game();
        g.setHomeTeam(homeTeam);
        g.setAwayTeam(awayTeam);
        g.setStatus(Game.GameStatus.FINAL);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setNeutralSite(false);
        g.setSeason(s);
        g.setGameDate(date.atTime(20, 0));
        return gameRepo.save(g);
    }

    @Test
    void predict_gameNotFound_throws404() {
        assertThatThrownBy(() -> service.predict(999L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void predict_postponedGame_returnsNullPredictions() {
        // Ratings ARE available — if predictions still return non-null, the status
        // filter is broken. With ratings absent, the test would pass trivially.
        addAllRatings(5.0, 2.0, 2.0, 75.0, 70.0, 2.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.POSTPONED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNull();
        assertThat(result.bradleyTerry()).isNull();
        assertThat(result.masseyTotal()).isNull();
    }

    @Test
    void predict_cancelledGame_returnsNullPredictions() {
        addAllRatings(5.0, 2.0, 2.0, 75.0, 70.0, 2.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.CANCELLED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNull();
        assertThat(result.bradleyTerry()).isNull();
        assertThat(result.masseyTotal()).isNull();
    }

    @Test
    void predict_noRatingSnapshots_returnNullPredictions() {
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNull();
        assertThat(result.bradleyTerry()).isNull();
        assertThat(result.masseyTotal()).isNull();
    }

    @Test
    void predict_snapshotOnGameDay_notUsed() {
        // Snapshot dated ON the game date should not be picked up (strictly before)
        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE, 5.0, GAME_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE, 2.0, GAME_DATE);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNull();
    }

    @Test
    void predict_masseySpreadCalculation() {
        // spread = homeRating - awayRating + hca = 5 - 2 + 2 = 5
        addAllRatings(5.0, 2.0, 2.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNotNull();
        assertThat(result.massey().spread()).isCloseTo(5.0, within(0.001));
    }

    @Test
    void predict_masseyTotalCalculation() {
        // total = homeTotal + awayTotal + hcaTotal = 75 + 70 + 2 = 147
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 2.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.masseyTotal()).isNotNull();
        assertThat(result.masseyTotal().total()).isCloseTo(147.0, within(0.001));
    }

    @Test
    void predict_bradleyTerryProbabilities_sumToOne() {
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.bradleyTerry()).isNotNull();
        double pHome = result.bradleyTerry().homeWinProbability();
        double pAway = result.bradleyTerry().awayWinProbability();
        assertThat(pHome + pAway).isCloseTo(1.0, within(0.0001));
        assertThat(pHome).isGreaterThan(0);
        assertThat(pAway).isGreaterThan(0);
    }

    @Test
    void predict_bradleyTerryWeighted_probabilitiesSumToOne() {
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.bradleyTerryWeighted()).isNotNull();
        double pHome = result.bradleyTerryWeighted().homeWinProbability();
        double pAway = result.bradleyTerryWeighted().awayWinProbability();
        assertThat(pHome + pAway).isCloseTo(1.0, within(0.0001));
        assertThat(pHome).isGreaterThan(0);
        assertThat(pAway).isGreaterThan(0);
    }

    @Test
    void predict_noWeightedBtSnapshots_weightedBlockIsNull() {
        // Only add standard BT ratings, not weighted — weighted block should be null
        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE, 5.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE, 2.0, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE, "hca", 0.0, SNAPSHOT_DATE);
        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE_TOTALS, 75.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE_TOTALS, 70.0, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", 0.0, SNAPSHOT_DATE);
        addRatingSnapshot(homeTeam, BradleyTerryRatingService.MODEL_TYPE, 1.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, BradleyTerryRatingService.MODEL_TYPE, 0.0, SNAPSHOT_DATE);
        addParamSnapshot(BradleyTerryRatingService.MODEL_TYPE, "hca", 0.0, SNAPSHOT_DATE);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.bradleyTerry()).isNotNull();
        assertThat(result.bradleyTerryWeighted()).isNull();
    }

    @Test
    void predict_masseyTotalIncludesIntercept() {
        // intercept = 140, β_h = 5, β_a = 2, δ = 0 → total = 5 + 2 + 140 = 147
        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE_TOTALS, 5.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE_TOTALS, 2.0, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE_TOTALS, "intercept", 140.0, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", 0.0, SNAPSHOT_DATE);
        // Massey and BT needed to form a complete game (not required for masseyTotal, but added for completeness)
        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE, 0.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE, 0.0, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE, "hca", 0.0, SNAPSHOT_DATE);
        addRatingSnapshot(homeTeam, BradleyTerryRatingService.MODEL_TYPE, 0.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, BradleyTerryRatingService.MODEL_TYPE, 0.0, SNAPSHOT_DATE);
        addParamSnapshot(BradleyTerryRatingService.MODEL_TYPE, "hca", 0.0, SNAPSHOT_DATE);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.masseyTotal()).isNotNull();
        assertThat(result.masseyTotal().total()).isCloseTo(147.0, within(0.001));
    }

    @Test
    void predict_bradleyTerry_favoriteHasNegativeMoneyline() {
        // btHome >> btAway → home is heavy favourite → negative home moneyline
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 5.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.bradleyTerry().homeImpliedMoneyline()).isNegative();
        assertThat(result.bradleyTerry().awayImpliedMoneyline()).isPositive();
    }

    @Test
    void predict_withBettingOdds_passedThrough() {
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        BettingOdds odds = new BettingOdds();
        odds.setGame(game);
        odds.setSpread(new BigDecimal("-3.5"));
        odds.setOverUnder(new BigDecimal("145.5"));
        oddsRepo.save(odds);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.bookSpread()).isEqualByComparingTo(new BigDecimal("-3.5"));
        assertThat(result.bookOverUnder()).isEqualByComparingTo(new BigDecimal("145.5"));
    }

    @Test
    void predict_withoutBettingOdds_bookFieldsNull() {
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.bookSpread()).isNull();
        assertThat(result.bookOverUnder()).isNull();
    }

    @Test
    void predict_finalGame_includesActualScores() {
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.FINAL, GAME_DATE);
        game.setHomeScore(85);
        game.setAwayScore(78);
        gameRepo.save(game);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.actualHomeScore()).isEqualTo(85);
        assertThat(result.actualAwayScore()).isEqualTo(78);
        assertThat(result.actualMargin()).isEqualTo(7);
        assertThat(result.actualTotal()).isEqualTo(163);
    }

    @Test
    void getUpcoming_clampsAbove30Days() {
        // Game inside 30-day cap; game outside it. With days=100, an unclamped
        // implementation would include both — clamping must exclude the +35-day one.
        Game inWindow  = mkScheduledAt(LocalDateTime.now().plusDays(25));
        Game outWindow = mkScheduledAt(LocalDateTime.now().plusDays(35));

        List<PredictionResult> results = service.getUpcoming(100);

        assertThat(results).extracting(PredictionResult::gameId)
                .contains(inWindow.getId())
                .doesNotContain(outWindow.getId());
    }

    @Test
    void getUpcoming_clampsBelow1Day() {
        // Game inside 1-day clamp; game well outside. With days=0, clamp must
        // pull the window up to 1 day so the near game is returned.
        Game inWindow  = mkScheduledAt(LocalDateTime.now().plusHours(12));
        Game outWindow = mkScheduledAt(LocalDateTime.now().plusDays(5));

        List<PredictionResult> results = service.getUpcoming(0);

        assertThat(results).extracting(PredictionResult::gameId)
                .contains(inWindow.getId())
                .doesNotContain(outWindow.getId());
    }

    private Game mkScheduledAt(LocalDateTime when) {
        Game g = new Game();
        g.setHomeTeam(homeTeam);
        g.setAwayTeam(awayTeam);
        g.setStatus(Game.GameStatus.SCHEDULED);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(when);
        return gameRepo.save(g);
    }

    @Test
    void getUpcoming_returnsOnlyScheduledGames() {
        LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);

        // SCHEDULED game in window
        Game scheduled = new Game();
        scheduled.setHomeTeam(homeTeam);
        scheduled.setAwayTeam(awayTeam);
        scheduled.setStatus(Game.GameStatus.SCHEDULED);
        scheduled.setNeutralSite(false);
        scheduled.setSeason(season);
        scheduled.setGameDate(tomorrow);
        gameRepo.save(scheduled);

        // FINAL game in same window — should be excluded
        Game finalGame = new Game();
        finalGame.setHomeTeam(homeTeam);
        finalGame.setAwayTeam(awayTeam);
        finalGame.setStatus(Game.GameStatus.FINAL);
        finalGame.setNeutralSite(false);
        finalGame.setSeason(season);
        finalGame.setGameDate(tomorrow.plusHours(2));
        finalGame.setHomeScore(80);
        finalGame.setAwayScore(70);
        gameRepo.save(finalGame);

        List<PredictionResult> results = service.getUpcoming(7);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).gameId()).isEqualTo(scheduled.getId());
    }

    @Test
    void predict_teamSummaryPopulatedCorrectly() {
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.homeTeam().id()).isEqualTo(homeTeam.getId());
        assertThat(result.homeTeam().name()).isEqualTo("Alabama");
        assertThat(result.awayTeam().id()).isEqualTo(awayTeam.getId());
        assertThat(result.awayTeam().name()).isEqualTo("Auburn");
    }

    // ── Adjusted efficiency (ADJ_EFF, spec W3) ────────────────────────────────

    /** off_h 5, def_h 2, off_a −1, def_a 1, τ_h 3, τ_a −1, μ 100, η 2, ν 68. */
    private void addAdjRatings() {
        addRatingSnapshot(homeTeam, "ADJ_OFF", 5.0, SNAPSHOT_DATE);
        addRatingSnapshot(homeTeam, "ADJ_DEF", 2.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, "ADJ_OFF", -1.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, "ADJ_DEF", 1.0, SNAPSHOT_DATE);
        addRatingSnapshot(homeTeam, "ADJ_TEMPO", 3.0, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, "ADJ_TEMPO", -1.0, SNAPSHOT_DATE);
        addParamSnapshot("ADJ_OFF", "eff_intercept", 100.0, SNAPSHOT_DATE);
        addParamSnapshot("ADJ_OFF", "eff_hca", 2.0, SNAPSHOT_DATE);
        addParamSnapshot("ADJ_TEMPO", "tempo_intercept", 68.0, SNAPSHOT_DATE);
    }

    @Test
    void predict_adjEfficiencyCalculation() {
        addAdjRatings();
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        // poss = 68 + 3 − 1 = 70; eh = 100 + 5 − 1 + 2 = 106; ea = 100 − 1 − 2 − 2 = 95
        // spread = 11·70/100 = 7.7; total = 201·70/100 = 140.7
        assertThat(result.adjEfficiency()).isNotNull();
        assertThat(result.adjEfficiency().spread()).isCloseTo(7.7, within(1e-9));
        assertThat(result.adjEfficiency().total()).isCloseTo(140.7, within(1e-9));
        assertThat(result.adjEfficiency().homeWinProbability())
                .isCloseTo(WinProbability.fromMargin(7.7, 11.0), within(1e-9));
    }

    @Test
    void predict_adjEfficiency_neutralSiteDropsHomeEdge() {
        addAdjRatings();
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);
        game.setNeutralSite(true);
        gameRepo.save(game);

        PredictionResult result = service.predict(game.getId());

        // η gone: eh = 104, ea = 97 → spread = 7·0.7 = 4.9; total unchanged (η cancels in the sum)
        assertThat(result.adjEfficiency().spread()).isCloseTo(4.9, within(1e-9));
        assertThat(result.adjEfficiency().total()).isCloseTo(140.7, within(1e-9));
    }

    @Test
    void predict_adjEfficiency_missingTempoSnapshotMeansNoPrediction() {
        addAdjRatings();
        // massey present so the rest of the prediction is unaffected
        addAllRatings(5.0, 2.0, 0.0, 75.0, 70.0, 0.0, 1.0, 0.0, 0.0);
        // wipe the away team's tempo snapshot
        ratingRepo.findLatestBefore(awayTeam.getId(), season.getId(), "ADJ_TEMPO", GAME_DATE)
                .ifPresent(ratingRepo::delete);
        Game game = mkGame(Game.GameStatus.SCHEDULED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.adjEfficiency()).isNull();
        assertThat(result.massey()).isNotNull();
    }
}
