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
        popStatRepo.deleteAll();
        snapshotRepo.deleteAll();
        oddsRepo.deleteAll();
        paramRepo.deleteAll();
        ratingRepo.deleteAll();
        statsRepo.deleteAll();
        gameRepo.deleteAll();
        membershipRepo.deleteAll();
        teamRepo.deleteAll();
        conferenceRepo.deleteAll();
        seasonRepo.deleteAll();

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

        addRatingSnapshot(homeTeam, MasseyRatingService.MODEL_TYPE_TOTAL, mtH, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, MasseyRatingService.MODEL_TYPE_TOTAL, mtA, SNAPSHOT_DATE);
        addParamSnapshot(MasseyRatingService.MODEL_TYPE_TOTAL, "hca_total", hcaTotal, SNAPSHOT_DATE);

        addRatingSnapshot(homeTeam, BradleyTerryRatingService.MODEL_TYPE, btH, SNAPSHOT_DATE);
        addRatingSnapshot(awayTeam, BradleyTerryRatingService.MODEL_TYPE, btA, SNAPSHOT_DATE);
        addParamSnapshot(BradleyTerryRatingService.MODEL_TYPE, "hca", btAlpha, SNAPSHOT_DATE);
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    void predict_gameNotFound_throws404() {
        assertThatThrownBy(() -> service.predict(999L))
                .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void predict_postponedGame_returnsNullPredictions() {
        Game game = mkGame(Game.GameStatus.POSTPONED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNull();
        assertThat(result.bradleyTerry()).isNull();
        assertThat(result.masseyTotal()).isNull();
    }

    @Test
    void predict_cancelledGame_returnsNullPredictions() {
        Game game = mkGame(Game.GameStatus.CANCELLED, GAME_DATE);

        PredictionResult result = service.predict(game.getId());

        assertThat(result.massey()).isNull();
        assertThat(result.bradleyTerry()).isNull();
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
        // No scheduled games exist — just verify it runs without error
        List<PredictionResult> results = service.getUpcoming(100);
        assertThat(results).isEmpty();
    }

    @Test
    void getUpcoming_clampsBelow1Day() {
        List<PredictionResult> results = service.getUpcoming(0);
        assertThat(results).isEmpty();
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
}
