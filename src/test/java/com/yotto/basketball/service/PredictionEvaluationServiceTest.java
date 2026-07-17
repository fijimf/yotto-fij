package com.yotto.basketball.service;

import com.yotto.basketball.BaseIntegrationTest;
import com.yotto.basketball.entity.BettingOdds;
import com.yotto.basketball.entity.Game;
import com.yotto.basketball.entity.PowerModelParamSnapshot;
import com.yotto.basketball.entity.PredictionEvaluation;
import com.yotto.basketball.entity.Season;
import com.yotto.basketball.entity.Team;
import com.yotto.basketball.entity.TeamPowerRatingSnapshot;
import com.yotto.basketball.repository.BettingOddsRepository;
import com.yotto.basketball.repository.GameRepository;
import com.yotto.basketball.repository.PowerModelParamSnapshotRepository;
import com.yotto.basketball.repository.PredictionEvaluationRepository;
import com.yotto.basketball.repository.SeasonRepository;
import com.yotto.basketball.repository.TeamPowerRatingSnapshotRepository;
import com.yotto.basketball.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PredictionEvaluationServiceTest extends BaseIntegrationTest {

    private static final LocalDate SNAPSHOT_DATE = LocalDate.of(2025, 1, 10);
    private static final LocalDateTime GAME_DATE = LocalDateTime.of(2025, 1, 15, 20, 0);

    @Autowired PredictionEvaluationService evaluationService;
    @Autowired PredictionEvaluationRepository evaluationRepo;
    @Autowired SeasonRepository seasonRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired GameRepository gameRepo;
    @Autowired BettingOddsRepository oddsRepo;
    @Autowired TeamPowerRatingSnapshotRepository ratingRepo;
    @Autowired PowerModelParamSnapshotRepository paramRepo;

    private Season season;
    private Team home;
    private Team away;

    @BeforeEach
    void seed() {
        season = new Season();
        season.setYear(2025);
        season.setStartDate(LocalDate.of(2024, 11, 1));
        season.setEndDate(LocalDate.of(2025, 4, 30));
        season = seasonRepo.save(season);

        home = mkTeam("Duke", "1");
        away = mkTeam("UNC", "2");

        // Massey: spread = 10 − 4 + 3 (hca) = 9
        addRating(home, MasseyRatingService.MODEL_TYPE, 10.0);
        addRating(away, MasseyRatingService.MODEL_TYPE, 4.0);
        addParam(MasseyRatingService.MODEL_TYPE, "hca", 3.0);

        // Massey Totals: total = 70 + 68 + 8 (intercept) + 2 (hca_total) = 148
        addRating(home, MasseyRatingService.MODEL_TYPE_TOTALS, 70.0);
        addRating(away, MasseyRatingService.MODEL_TYPE_TOTALS, 68.0);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "intercept", 8.0);
        addParam(MasseyRatingService.MODEL_TYPE_TOTALS, "hca_total", 2.0);

        // Bradley-Terry: p = σ(0.8 − 0.3 + 0.1) = σ(0.6)
        addRating(home, BradleyTerryRatingService.MODEL_TYPE, 0.8);
        addRating(away, BradleyTerryRatingService.MODEL_TYPE, 0.3);
        addParam(BradleyTerryRatingService.MODEL_TYPE, "hca", 0.1);

        // Weighted Bradley-Terry: p = σ(0.9 − 0.2 + 0.1) = σ(0.8)
        addRating(home, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 0.9);
        addRating(away, BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, 0.2);
        addParam(BradleyTerryRatingService.MODEL_TYPE_WEIGHTED, "hca", 0.1);
    }

    // ── evaluateSeason ────────────────────────────────────────────────────────

    @Test
    void evaluateSeason_writesOneRowPerModelWithCorrectErrors() {
        Game game = mkFinalGame("g1", 80, 75);           // margin +5, total 155
        addOdds(game, "-6.5", "150.5", -250, +205);      // book: home by 6.5

        int evaluated = evaluationService.evaluateSeason(2025);

        assertThat(evaluated).isEqualTo(1);
        Map<String, PredictionEvaluation> byModel = evaluationRepo.findByGameId(game.getId()).stream()
                .collect(Collectors.toMap(PredictionEvaluation::getModelType, Function.identity()));
        assertThat(byModel).containsOnlyKeys("MASSEY", "MASSEY_TOTALS", "BRADLEY_TERRY", "BRADLEY_TERRY_W", "BOOK");

        PredictionEvaluation massey = byModel.get("MASSEY");
        assertThat(massey.getPredictedSpread()).isCloseTo(9.0, within(1e-9));    // 10 − 4 + 3
        assertThat(massey.getSpreadError()).isCloseTo(-4.0, within(1e-9));       // 5 − 9
        assertThat(massey.getPredictedTotal()).isNull();
        assertThat(massey.getActualMargin()).isEqualTo(5);
        assertThat(massey.getActualTotal()).isEqualTo(155);
        assertThat(massey.getHomeWon()).isTrue();

        PredictionEvaluation totals = byModel.get("MASSEY_TOTALS");
        assertThat(totals.getPredictedTotal()).isCloseTo(148.0, within(1e-9));   // 70 + 68 + 8 + 2
        assertThat(totals.getTotalError()).isCloseTo(7.0, within(1e-9));         // 155 − 148

        double sigma06 = 1.0 / (1.0 + Math.exp(-0.6));
        assertThat(byModel.get("BRADLEY_TERRY").getPredictedHomeWinProb()).isCloseTo(sigma06, within(1e-9));
        double sigma08 = 1.0 / (1.0 + Math.exp(-0.8));
        assertThat(byModel.get("BRADLEY_TERRY_W").getPredictedHomeWinProb()).isCloseTo(sigma08, within(1e-9));

        // BOOK: spread is a handicap (−6.5 = home by 6.5) → predicted margin +6.5
        PredictionEvaluation book = byModel.get("BOOK");
        assertThat(book.getPredictedSpread()).isCloseTo(6.5, within(1e-9));
        assertThat(book.getSpreadError()).isCloseTo(-1.5, within(1e-9));         // 5 − 6.5
        assertThat(book.getPredictedTotal()).isCloseTo(150.5, within(1e-9));
        // De-vigged moneyline prob: home −250 → 250/350; away +205 → 100/305
        double ih = 250.0 / 350.0, ia = 100.0 / 305.0;
        assertThat(book.getPredictedHomeWinProb()).isCloseTo(ih / (ih + ia), within(1e-9));
    }

    @Test
    void evaluateSeason_secondRunIsIncremental() {
        mkFinalGame("g1", 80, 75);

        assertThat(evaluationService.evaluateSeason(2025)).isEqualTo(1);
        long rowsAfterFirst = evaluationRepo.count();

        assertThat(evaluationService.evaluateSeason(2025)).isZero();
        assertThat(evaluationRepo.count()).isEqualTo(rowsAfterFirst);
    }

    @Test
    void evaluateSeason_skipsGamesWithoutPreGameSnapshots() {
        // Game the day before the snapshots exist — no pre-game information available
        Game early = new Game();
        early.setEspnId("early");
        early.setHomeTeam(home);
        early.setAwayTeam(away);
        early.setStatus(Game.GameStatus.FINAL);
        early.setHomeScore(70);
        early.setAwayScore(60);
        early.setSeason(season);
        early.setGameDate(SNAPSHOT_DATE.minusDays(1).atTime(19, 0));
        gameRepo.save(early);

        assertThat(evaluationService.evaluateSeason(2025)).isZero();
        assertThat(evaluationRepo.count()).isZero();
    }

    @Test
    void evaluateSeason_ignoresScheduledGames() {
        Game g = mkFinalGame("g1", 80, 75);
        g.setStatus(Game.GameStatus.SCHEDULED);
        g.setHomeScore(null);
        g.setAwayScore(null);
        gameRepo.save(g);

        assertThat(evaluationService.evaluateSeason(2025)).isZero();
    }

    @Test
    void rebuildSeason_replacesExistingRows() {
        Game game = mkFinalGame("g1", 80, 75);
        assertThat(evaluationService.evaluateSeason(2025)).isEqualTo(1);
        List<Long> firstIds = evaluationRepo.findByGameId(game.getId()).stream()
                .map(PredictionEvaluation::getId).toList();

        assertThat(evaluationService.rebuildSeason(2025)).isEqualTo(1);

        List<PredictionEvaluation> rebuilt = evaluationRepo.findByGameId(game.getId());
        assertThat(rebuilt).hasSize(4);   // no odds on this game → no BOOK row
        assertThat(rebuilt).extracting(PredictionEvaluation::getId).doesNotContainAnyElementsOf(firstIds);
    }

    // ── Aggregates ────────────────────────────────────────────────────────────

    @Test
    void aggregateQueries_computeMetricsPerModel() {
        mkFinalGame("g1", 80, 75);      // margin +5: Massey (spread 9) picked the winner
        mkFinalGame("g2", 60, 70);      // margin −10: Massey picked the loser
        evaluationService.evaluateSeason(2025);

        LocalDate from = LocalDate.of(1900, 1, 1);
        var spread = evaluationRepo.spreadMetrics(season.getId(), from, true, List.of("NONE"));
        var masseyRow = spread.stream().filter(r -> r.getModelType().equals("MASSEY")).findFirst().orElseThrow();
        assertThat(masseyRow.getN()).isEqualTo(2);
        // errors: 5−9 = −4 and −10−9 = −19 → MAE 11.5, RMSE sqrt((16+361)/2)
        assertThat(masseyRow.getMae()).isCloseTo(11.5, within(1e-6));
        assertThat(masseyRow.getRmse()).isCloseTo(Math.sqrt((16.0 + 361.0) / 2.0), within(1e-6));
        assertThat(masseyRow.getSideAccuracy()).isCloseTo(0.5, within(1e-6));

        var prob = evaluationRepo.probMetrics(season.getId(), from, true, List.of("NONE"));
        var btRow = prob.stream().filter(r -> r.getModelType().equals("BRADLEY_TERRY")).findFirst().orElseThrow();
        double p = 1.0 / (1.0 + Math.exp(-0.6));
        double expectedBrier = (Math.pow(p - 1, 2) + Math.pow(p - 0, 2)) / 2.0;
        assertThat(btRow.getBrier()).isCloseTo(expectedBrier, within(1e-6));
        assertThat(btRow.getAccuracy()).isCloseTo(0.5, within(1e-6));

        var buckets = evaluationRepo.calibrationBuckets(season.getId(), from, true, List.of("NONE"));
        assertThat(buckets).isNotEmpty();
        var btBuckets = buckets.stream().filter(b -> b.getModelType().equals("BRADLEY_TERRY")).toList();
        assertThat(btBuckets).hasSize(1);   // both games share the same predicted prob
        assertThat(btBuckets.get(0).getN()).isEqualTo(2);
        assertThat(btBuckets.get(0).getActualRate()).isCloseTo(0.5, within(1e-6));

        assertThat(evaluationRepo.findEvaluatedSeasonYears()).containsExactly(2025);
    }

    // ── Moneyline de-vig helper ───────────────────────────────────────────────

    @Test
    void impliedHomeWinProb_devigsMoneylinePair() {
        // Symmetric −110/−110 → exactly 0.5 after removing the vig
        assertThat(PredictionEvaluationService.impliedHomeWinProb(-110, -110)).isCloseTo(0.5, within(1e-9));
        assertThat(PredictionEvaluationService.impliedHomeWinProb(null, +150)).isNull();
        assertThat(PredictionEvaluationService.impliedHomeWinProb(-200, null)).isNull();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private Team mkTeam(String name, String espnId) {
        Team t = new Team();
        t.setName(name);
        t.setEspnId(espnId);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Game mkFinalGame(String espnId, int homeScore, int awayScore) {
        Game g = new Game();
        g.setEspnId(espnId);
        g.setHomeTeam(home);
        g.setAwayTeam(away);
        g.setStatus(Game.GameStatus.FINAL);
        g.setHomeScore(homeScore);
        g.setAwayScore(awayScore);
        g.setNeutralSite(false);
        g.setSeason(season);
        g.setGameDate(GAME_DATE);
        return gameRepo.save(g);
    }

    private void addOdds(Game game, String spread, String overUnder, Integer homeMl, Integer awayMl) {
        BettingOdds bo = new BettingOdds();
        bo.setGame(game);
        bo.setSpread(new BigDecimal(spread));
        bo.setOverUnder(new BigDecimal(overUnder));
        bo.setHomeMoneyline(homeMl);
        bo.setAwayMoneyline(awayMl);
        oddsRepo.save(bo);
    }

    private void addRating(Team team, String modelType, double rating) {
        TeamPowerRatingSnapshot s = new TeamPowerRatingSnapshot();
        s.setTeam(team);
        s.setSeason(season);
        s.setModelType(modelType);
        s.setSnapshotDate(SNAPSHOT_DATE);
        s.setRating(rating);
        s.setGamesPlayed(10);
        s.setCalculatedAt(LocalDateTime.now());
        ratingRepo.save(s);
    }

    private void addParam(String modelType, String paramName, double value) {
        PowerModelParamSnapshot p = new PowerModelParamSnapshot();
        p.setSeason(season);
        p.setModelType(modelType);
        p.setSnapshotDate(SNAPSHOT_DATE);
        p.setParamName(paramName);
        p.setParamValue(value);
        p.setCalculatedAt(LocalDateTime.now());
        paramRepo.save(p);
    }
}
